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
import org.printerbridge.exception.*;
import org.printerbridge.printer.PrintContentType;
import org.printerbridge.service.discovery.*;
import org.printerbridge.service.portInfo.*;
import org.printerbridge.service.transport.BluetoothPrintTransport;
import org.printerbridge.service.transport.LinuxBluetoothPrintTransport;
import org.printerbridge.service.transport.WindowsBluetoothPrintTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PrintJobService {

    private static final Logger LOG = LoggerFactory.getLogger(PrintJobService.class);

    // How long a request waits for the single Bluetooth connection to free up (cf. CLAUDE.md).
    private static final long LOCK_WAIT_SECONDS = 10;

    // A dead/misidentified Bluetooth link can let open() succeed but then block forever on the
    // write, with no cross-platform native timeout (jSerialComm's own is Windows-only). Bounded
    // here by running the write on a separate thread and force-closing if it doesn't return in
    // time — closing unblocks a stuck native write reliably.
    private static final long WRITE_TIMEOUT_SECONDS = 10;

    // write()+flush() returning only means the bytes reached the OS's output buffer, not that
    // they were physically transmitted yet — Bluetooth SPP is much slower than a local buffer
    // copy, and closing the channel while data is still queued can cut the connection before it's
    // sent (confirmed on real hardware, cf. CLAUDE.md). This delay lets the transmission drain
    // before closing.
    private static final long POST_WRITE_SETTLE_MILLIS = 500;

    // Shared rather than one-per-call: a stuck native write pins its thread regardless (even a
    // virtual thread's carrier gains nothing), so a cached pool at least avoids paying thread
    // create/teardown cost on every normal (fast) write.
    private static final ExecutorService WRITE_EXECUTOR =
            Executors.newCachedThreadPool(PrintJobService::newDaemonThread);

    // Resolved once: the OS doesn't change at runtime. Windows keeps the validated jSerialComm
    // implementation; Linux gets its own, iterated on independently (cf. CLAUDE.md).
    private static final BluetoothPrintTransport BLUETOOTH_TRANSPORT =
            LinuxBluetoothPortInfo.isLinux() ? new LinuxBluetoothPrintTransport() : new WindowsBluetoothPrintTransport();

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
     * Injectable constructor for tests: resolves printer ids without touching real hardware. Real
     * Bluetooth port lookup goes through WMI/{@code rfcomm}, an external-process call measured at
     * ~7s on real hardware — this lets tests (including "unknown id" error-path tests that don't
     * care about discovery) supply id -&gt; Optional.empty() directly, skipping that cost entirely.
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
     * Actually attempts to print a small generated payload, so a caller can learn whether a
     * printer really works end to end — not just that its port/service can be opened (a dead
     * Bluetooth link can still report as reachable, cf. CLAUDE.md).
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

        // Only released synchronously in the normal/failed-fast cases — on a write timeout, the
        // abandoned write is still running on WRITE_EXECUTOR and may still be touching the port, so
        // unlocking here would let a second job race it for the same RFCOMM connection. The lock is
        // instead released once that abandoned write actually finishes (see StuckWriteException
        // handling below).
        CompletableFuture<Void> abandonedWrite = null;
        try {
            BluetoothPrintTransport.Channel channel;
            try {
                channel = BLUETOOTH_TRANSPORT.open(port);
            } catch (IOException e) {
                throw new PrintJobException(
                        "Could not open Bluetooth port " + port.getSystemPortName() + ": " + e.getMessage());
            }
            try {
                LOG.info("Printing {} bytes to {} ({})", payload.length, printerId, port.getSystemPortName());
                writeWithTimeout(() -> channel.write(payload), channel::close, port.getSystemPortName());
                LOG.info("Write to {} completed without error", printerId);
            } catch (StuckWriteException e) {
                abandonedWrite = e.pendingWrite;
                PrinterLocks.markStuck(printerId);
                LOG.warn("Write to {} got stuck: {}", printerId, e.getMessage());
                throw new PrintJobException(e.getMessage());
            } finally {
                channel.close();
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

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private static void writeWithTimeout(IoAction writeAction, Runnable forceClose, String portDescription) {
        CompletableFuture<Void> write = CompletableFuture.runAsync(() -> {
            try {
                writeAction.run();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, WRITE_EXECUTOR);

        try {
            write.get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Thread.sleep(POST_WRITE_SETTLE_MILLIS);
        } catch (TimeoutException e) {
            // Force-closing is what unblocks the stuck write; the caller's own close-in-a-finally
            // is then a harmless no-op. The write task is left running on WRITE_EXECUTOR rather than
            // cancelled, since interrupting a thread blocked in native/blocking I/O does nothing.
            forceClose.run();
            throw new StuckWriteException(
                    "Timed out writing to Bluetooth port " + portDescription + " (dead or wrong link)", write);
        } catch (ExecutionException e) {
            throw new PrintJobException("Failed to write to Bluetooth port: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrintJobException("Interrupted while writing to Bluetooth port " + portDescription);
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
            // PDFBox rendering can fail in ways that aren't IOException/PrintException for a
            // malformed-but-openable PDF; without this, the exception would escape to the WS handler
            // with no PrintResult at all. Logged at ERROR (with cause) so an unrelated bug isn't
            // silently mislabeled as a PDF problem, even though the client-facing message stays generic.
            LOG.error("Unexpected failure while rendering/printing a PDF job", e);
            throw new PrintJobException("Failed to render or print PDF: " + e.getMessage(), e);
        }
    }
}
