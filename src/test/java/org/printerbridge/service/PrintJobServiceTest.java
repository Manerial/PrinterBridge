package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.printerbridge.printer.PrintContentType;

class PrintJobServiceTest {

    // Fake lookups instead of PrintJobService's real constructor: print()/testPrint() previously
    // always went through real Bluetooth (WMI) discovery first, even for an id these tests already
    // know isn't real — on Windows that's a ~7.5s external call per test (measured, not a code bug,
    // see WindowsBluetoothPortInfo), for a test that has nothing to do with hardware. This is what
    // the injectable constructor (correctif audit) exists for.
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

    // Sanity check that PrintJobService's real (no-arg) constructor is actually wired to the real
    // BluetoothPrinterDiscovery/NetworkPrinterDiscovery, not just that the fake-backed path above
    // works — hits real hardware discovery, so tagged "hardware" like the rest (pom.xml).
    @Test
    @Tag("hardware")
    void printOnTheRealConstructorRejectsAnUnknownPrinterId() {
        PrintJobException exception = assertThrows(PrintJobException.class,
                () -> new PrintJobService().print("unknown", PrintContentType.ESC_POS, new byte[]{1}));

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
