package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.printerbridge.exception.*;
import org.printerbridge.printer.PrintContentType;

class PrintJobServiceTest {

    // Fake lookups instead of the real constructor: print()/testPrint() would otherwise always go
    // through real Bluetooth (WMI) discovery first, ~7.5s per call on Windows (see
    // WindowsBluetoothPortInfo), for a test that has nothing to do with hardware.
    private final PrintJobService printJobService = new PrintJobService(id -> Optional.empty(), id -> Optional.empty());

    @Test
    void printRejectsAnUnknownPrinterId() {
        PrintJobException exception = assertThrows(PrintJobException.class,
                () -> printJobService.print("unknown", PrintContentType.ESC_POS, new byte[]{1}));

        assertTrue(exception.getMessage().contains("Unknown printer id"));
    }

    @Test
    void testPrintRejectsAnUnknownPrinterId() {
        PrintJobException exception = assertThrows(PrintJobException.class,
                () -> printJobService.testPrint("unknown"));

        assertTrue(exception.getMessage().contains("Unknown printer id"));
    }

    // Sanity check that the real (no-arg) constructor is actually wired to the real discovery
    // classes, not just that the fake-backed path above works — hits real hardware, tagged accordingly.
    @Test
    @Tag("hardware")
    void printOnTheRealConstructorRejectsAnUnknownPrinterId() {
        PrintJobException exception = assertThrows(PrintJobException.class,
                () -> new PrintJobService().print("unknown", PrintContentType.ESC_POS, new byte[]{1}));

        assertTrue(exception.getMessage().contains("Unknown printer id"));
    }

    // Exercised directly rather than through print()/testPrint(), which only reach this check by
    // first finding a real Bluetooth port or network PrintService — not available on a bare CI runner.

    @Test
    void requireContentTypeAcceptsAMatchingType() {
        assertDoesNotThrow(
                () -> PrintJobService.requireContentType(PrintContentType.PDF, PrintContentType.PDF, "Network/A4"));
    }

    @Test
    void requireContentTypeRejectsEscPosForNetworkTransport() {
        PrintJobException exception = assertThrows(PrintJobException.class, () -> PrintJobService
                .requireContentType(PrintContentType.ESC_POS, PrintContentType.PDF, "Network/A4"));

        assertTrue(exception.getMessage().contains("only accept PDF"));
    }

    @Test
    void requireContentTypeRejectsPdfForBluetoothTransport() {
        PrintJobException exception = assertThrows(PrintJobException.class, () -> PrintJobService
                .requireContentType(PrintContentType.PDF, PrintContentType.ESC_POS, "Bluetooth thermal"));

        assertTrue(exception.getMessage().contains("only accept ESC_POS"));
    }

    // Deliberately not tested here: a successful testPrint() against a real, discovered printer —
    // same reasoning as the WS print endpoint tests: it would actually attempt to print, which on
    // this dev machine risks a save dialog (Microsoft Print to PDF) or a real page on a shared
    // office copier. Validate manually against real hardware instead.
}
