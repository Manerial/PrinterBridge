package org.printerbridge.service;

import com.fazecast.jSerialComm.SerialPort;
import java.awt.print.Printable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.SimpleDoc;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.printerbridge.printer.PrintContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PrintJobService {

    private static final Logger LOG = LoggerFactory.getLogger(PrintJobService.class);

    // Placeholder pending the broader error/timeout policy decision (CLAUDE.md, still open) —
    // this only bounds how long a request waits for the single Bluetooth connection to free up.
    private static final long LOCK_WAIT_SECONDS = 10;

    // Confirmed against real hardware: a dead/misidentified Bluetooth link (cf. CLAUDE.md,
    // jSerialComm can't always tell a real device from a generic serial-port artifact) lets
    // openPort() succeed but then blocks forever on the write, with no cross-platform native
    // timeout available (jSerialComm's own write timeout is Windows-only). Bounded here instead
    // by running the write on a separate thread and force-closing the port if it doesn't return
    // in time — closing out from under a blocked native write reliably unblocks it.
    private static final long WRITE_TIMEOUT_SECONDS = 10;

    // Shared rather than one-per-call: jSerialComm's write() blocks in native (JNI) code, which
    // pins whatever thread runs it — including a virtual thread's carrier, with no benefit over a
    // platform thread — so a stuck write leaks a thread either way. A shared cached pool at least
    // avoids paying OS thread create/teardown cost on every single (normally fast) write.
    private static final ExecutorService WRITE_EXECUTOR =
            Executors.newCachedThreadPool(PrintJobService::newDaemonThread);

    private final Function<String, Optional<SerialPort>> bluetoothPortLookup;
    private final Function<String, Optional<PrintService>> networkServiceLookup;

    /**
     * Real-hardware constructor: resolves ids against the actual OS-discovered Bluetooth ports and
     * network print services (cf. {@link BluetoothPrinterDiscovery}/{@link NetworkPrinterDiscovery}).
     */
    public PrintJobService() {
        this(BluetoothPrinterDiscovery::findPort, NetworkPrinterDiscovery::findService);
    }

    /**
     * Injectable constructor — lets callers (tests, mainly) resolve printer ids without touching
     * real hardware or the OS. Real Windows/Linux Bluetooth port lookup goes through WMI/`rfcomm`,
     * an external-process call that's consistently ~7s on real hardware (measured — not a code
     * bug, the WMI enumeration itself is that slow on at least one dev machine) — every automated
     * test that used to resolve a real id, including "unknown id" error-path tests that don't care
     * about discovery at all, paid that cost. This constructor is the fix: it lets those tests
     * supply id -&gt; Optional.empty() (or a specific fake id) directly, with no discovery latency.
     */
    public PrintJobService(Function<String, Optional<SerialPort>> bluetoothPortLookup,
            Function<String, Optional<PrintService>> networkServiceLookup) {
        this.bluetoothPortLookup = bluetoothPortLookup;
        this.networkServiceLookup = networkServiceLookup;
    }

    public void print(String printerId, PrintContentType contentType, byte[] payload) {
        Optional<SerialPort> port = bluetoothPortLookup.apply(printerId);
        if (port.isPresent()) {
            printViaBluetooth(printerId, port.get(), contentType, payload);
            return;
        }

        Optional<PrintService> service = networkServiceLookup.apply(printerId);
        if (service.isPresent()) {
            printViaNetwork(service.get(), contentType, payload);
            return;
        }

        throw new UnknownPrinterException(printerId);
    }

    /**
     * Actually attempts to print a small, generated test payload appropriate to the printer's
     * transport, so a caller (the PluriBourse backend) can learn whether a printer really works
     * end to end — not just that its port/service can be opened, which we've seen isn't enough
     * (cf. CLAUDE.md): a dead Bluetooth link can still report as reachable.
     */
    public void testPrint(String printerId) {
        Optional<SerialPort> port = bluetoothPortLookup.apply(printerId);
        if (port.isPresent()) {
            printViaBluetooth(printerId, port.get(), PrintContentType.ESC_POS, TestPrintPayloads.escPos(printerId));
            return;
        }

        Optional<PrintService> service = networkServiceLookup.apply(printerId);
        if (service.isPresent()) {
            printViaNetwork(service.get(), PrintContentType.PDF, TestPrintPayloads.pdf(printerId));
            return;
        }

        throw new UnknownPrinterException(printerId);
    }

    static void requireContentType(PrintContentType actual, PrintContentType expected, String transportDescription) {
        if (actual != expected) {
            throw new PrintJobException(
                    transportDescription + " printers only accept " + expected + " content, got " + actual);
        }
    }

    private static void printViaBluetooth(String printerId, SerialPort port, PrintContentType contentType,
            byte[] payload) {
        requireContentType(contentType, PrintContentType.ESC_POS, "Bluetooth thermal");

        Lock lock = PrinterLocks.forPrinter(printerId);
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrintJobException("Interrupted while waiting to print on " + printerId);
        }
        if (!acquired) {
            throw new PrintJobException("Printer " + printerId + " is busy printing another job, try again later");
        }

        // Only released synchronously in the normal/failed-fast cases. On a write timeout, the
        // abandoned write is still running on WRITE_EXECUTOR (see writeWithTimeout) and may still be
        // touching the port when this method returns — releasing the lock here would let a second
        // job for the same printer id race it for the RFCOMM connection, exactly what PrinterLocks
        // exists to prevent. In that case the lock is instead released once the abandoned write
        // actually finishes (see the StuckWriteException handling below).
        CompletableFuture<Void> abandonedWrite = null;
        try {
            if (!port.openPort()) {
                throw new PrintJobException("Could not open Bluetooth port " + port.getSystemPortName());
            }
            try {
                writeWithTimeout(port, payload);
            } catch (StuckWriteException e) {
                abandonedWrite = e.pendingWrite;
                PrinterLocks.markStuck(printerId);
                throw new PrintJobException(e.getMessage());
            } finally {
                port.closePort();
            }
        } finally {
            if (abandonedWrite == null) {
                lock.unlock();
            } else {
                CompletableFuture<Void> pendingWrite = abandonedWrite;
                pendingWrite.whenComplete((ignoredResult, ignoredError) -> {
                    PrinterLocks.clearStuck(printerId);
                    lock.unlock();
                });
            }
        }
    }

    /** Carries the still-running write task out of {@link #writeWithTimeout} so its caller can defer
     * releasing the printer lock until that abandoned write actually finishes, instead of the moment
     * it times out. */
    private static final class StuckWriteException extends RuntimeException {
        private final CompletableFuture<Void> pendingWrite;

        StuckWriteException(String message, CompletableFuture<Void> pendingWrite) {
            super(message);
            this.pendingWrite = pendingWrite;
        }
    }

    private static void writeWithTimeout(SerialPort port, byte[] payload) {
        CompletableFuture<Void> write = CompletableFuture.runAsync(() -> {
            try {
                port.getOutputStream().write(payload);
                port.getOutputStream().flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, WRITE_EXECUTOR);

        try {
            write.get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Force-closing out from under the stuck native write is what actually unblocks it;
            // the caller's own `finally { port.closePort(); }` will then be a harmless no-op.
            // The write task itself is left running on WRITE_EXECUTOR — it isn't cancelled,
            // since interrupting a thread pinned in native code wouldn't do anything anyway.
            port.closePort();
            throw new StuckWriteException(
                    "Timed out writing to Bluetooth port " + port.getSystemPortName() + " (dead or wrong link)",
                    write);
        } catch (ExecutionException e) {
            throw new PrintJobException("Failed to write to Bluetooth port: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrintJobException("Interrupted while writing to Bluetooth port " + port.getSystemPortName());
        }
    }

    private static Thread newDaemonThread(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        return thread;
    }

    private static void printViaNetwork(PrintService service, PrintContentType contentType, byte[] payload) {
        requireContentType(contentType, PrintContentType.PDF, "Network/A4");
        try (PDDocument document = Loader.loadPDF(payload)) {
            Printable printable = new PdfPrintable(document);
            Doc doc = new SimpleDoc(printable, DocFlavor.SERVICE_FORMATTED.PRINTABLE, null);
            DocPrintJob job = service.createPrintJob();
            job.print(doc, null);
        } catch (IOException e) {
            throw new PrintJobException("Failed to read PDF payload: " + e.getMessage());
        } catch (PrintException e) {
            throw new PrintJobException("Failed to submit print job: " + e.getMessage());
        } catch (RuntimeException e) {
            // PDFBox rendering (PdfPrintable, invoked synchronously by job.print()) can fail in ways
            // that aren't IOException/PrintException for a malformed-but-openable PDF; without this,
            // the exception would escape all the way to the WS handler and leave the caller hanging
            // with no PrintResult at all. Logged at ERROR with the full stack trace (and attached as
            // cause) so an unrelated programming error isn't silently mislabeled as a PDF problem —
            // the client-facing message stays generic, but the real cause is still diagnosable here.
            LOG.error("Unexpected failure while rendering/printing a PDF job", e);
            throw new PrintJobException("Failed to render or print PDF: " + e.getMessage(), e);
        }
    }
}
