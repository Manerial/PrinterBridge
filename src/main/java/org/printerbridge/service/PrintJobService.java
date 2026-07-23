package org.printerbridge.service;

import com.fazecast.jSerialComm.SerialPort;
import java.awt.print.Printable;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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
                port.getOutputStream().write(payload);
                port.getOutputStream().flush();
            } catch (IOException e) {
                throw new PrintJobException("Failed to write to Bluetooth port: " + e.getMessage());
            } finally {
                port.closePort();
            }
        } finally {
            lock.unlock();
        }
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
