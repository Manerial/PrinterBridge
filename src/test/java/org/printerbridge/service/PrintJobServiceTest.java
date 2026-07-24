package org.printerbridge.service;

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

    // Deliberately not tested here: a successful testPrint() against a real, discovered printer —
    // same reasoning as the WS print endpoint tests: it would actually attempt to print, which on
    // this dev machine risks a save dialog (Microsoft Print to PDF) or a real page on a shared
    // office copier. Validate manually against real hardware instead.
}
