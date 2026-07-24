package org.printerbridge.service;

import com.fazecast.jSerialComm.SerialPort;
import java.awt.print.Printable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.SimpleDoc;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.printerbridge.printer.PrintContentType;

public final class PrintJobService {

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

    private PrintJobService() {
    }

    public static void print(String printerId, PrintContentType contentType, byte[] payload) {
        Optional<SerialPort> port = BluetoothPrinterDiscovery.findPort(printerId);
        if (port.isPresent()) {
            printViaBluetooth(printerId, port.get(), contentType, payload);
            return;
        }

        Optional<PrintService> service = NetworkPrinterDiscovery.findService(printerId);
        if (service.isPresent()) {
            printViaNetwork(service.get(), contentType, payload);
            return;
        }

        throw new PrintJobException("Unknown printer id: " + printerId);
    }

    /**
     * Actually attempts to print a small, generated test payload appropriate to the printer's
     * transport, so a caller (the PluriBourse backend) can learn whether a printer really works
     * end to end — not just that its port/service can be opened, which we've seen isn't enough
     * (cf. CLAUDE.md): a dead Bluetooth link can still report as reachable.
     */
    public static void testPrint(String printerId) {
        Optional<SerialPort> port = BluetoothPrinterDiscovery.findPort(printerId);
        if (port.isPresent()) {
            printViaBluetooth(printerId, port.get(), PrintContentType.ESC_POS, TestPrintPayloads.escPos(printerId));
            return;
        }

        Optional<PrintService> service = NetworkPrinterDiscovery.findService(printerId);
        if (service.isPresent()) {
            printViaNetwork(service.get(), PrintContentType.PDF, TestPrintPayloads.pdf(printerId));
            return;
        }

        throw new PrintJobException("Unknown printer id: " + printerId);
    }

    private static void printViaBluetooth(String printerId, SerialPort port, PrintContentType contentType,
            byte[] payload) {
        if (contentType != PrintContentType.ESC_POS) {
            throw new PrintJobException("Bluetooth thermal printers only accept ESC_POS content, got " + contentType);
        }

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

        try {
            if (!port.openPort()) {
                throw new PrintJobException("Could not open Bluetooth port " + port.getSystemPortName());
            }
            try {
                writeWithTimeout(port, payload);
            } finally {
                port.closePort();
            }
        } finally {
            lock.unlock();
        }
    }

    private static void writeWithTimeout(SerialPort port, byte[] payload) {
        ExecutorService executor = Executors.newSingleThreadExecutor(PrintJobService::newDaemonThread);
        try {
            Future<?> write = executor.submit(() -> {
                port.getOutputStream().write(payload);
                port.getOutputStream().flush();
                return null;
            });
            write.get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Force-closing out from under the stuck native write is what actually unblocks it;
            // the caller's own `finally { port.closePort(); }` will then be a harmless no-op.
            port.closePort();
            throw new PrintJobException(
                    "Timed out writing to Bluetooth port " + port.getSystemPortName() + " (dead or wrong link)");
        } catch (ExecutionException e) {
            throw new PrintJobException("Failed to write to Bluetooth port: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrintJobException("Interrupted while writing to Bluetooth port " + port.getSystemPortName());
        } finally {
            executor.shutdownNow();
        }
    }

    private static Thread newDaemonThread(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        return thread;
    }

    private static void printViaNetwork(PrintService service, PrintContentType contentType, byte[] payload) {
        if (contentType != PrintContentType.PDF) {
            throw new PrintJobException("Network/A4 printers only accept PDF content, got " + contentType);
        }
        try (PDDocument document = Loader.loadPDF(payload)) {
            Printable printable = new PdfPrintable(document);
            Doc doc = new SimpleDoc(printable, DocFlavor.SERVICE_FORMATTED.PRINTABLE, null);
            DocPrintJob job = service.createPrintJob();
            job.print(doc, null);
        } catch (IOException e) {
            throw new PrintJobException("Failed to read PDF payload: " + e.getMessage());
        } catch (PrintException e) {
            throw new PrintJobException("Failed to submit print job: " + e.getMessage());
        }
    }
}
