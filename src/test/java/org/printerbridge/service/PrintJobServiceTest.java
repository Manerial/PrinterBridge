package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.printerbridge.printer.PrintContentType;

class PrintJobServiceTest {

    @Test
    void printRejectsAnUnknownPrinterId() {
        PrintJobException exception = assertThrows(PrintJobException.class,
                () -> PrintJobService.print("unknown", PrintContentType.ESC_POS, new byte[]{1}));

        assertTrue(exception.getMessage().contains("Unknown printer id"));
    }

    @Test
    void testPrintRejectsAnUnknownPrinterId() {
        PrintJobException exception = assertThrows(PrintJobException.class,
                () -> PrintJobService.testPrint("unknown"));

        assertTrue(exception.getMessage().contains("Unknown printer id"));
    }

    // requireContentType is what printViaBluetooth/printViaNetwork actually delegate to for their
    // mismatch check — exercised directly here because print()/testPrint() only ever reach that
    // check by first finding a real Bluetooth port or network PrintService for the given id, which
    // isn't available on a bare CI runner without matching hardware (see ApiServerTest, which skips
    // its equivalent case via Assumptions.assumeTrue for exactly that reason).

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
