package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterType;

class NetworkPrinterDiscoveryTest {

    @Test
    void discoversWhateverIsRegisteredAtOsLevelWithoutThrowing() {
        List<Printer> printers = assertDoesNotThrow(NetworkPrinterDiscovery::discover);

        for (Printer printer : printers) {
            assertNotNull(printer.id());
            assertTrue(printer.id().matches("[0-9a-f]{16}"));
            assertEquals(PrinterType.NETWORK, printer.type());
        }
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertEquals(Optional.empty(), NetworkPrinterDiscovery.findById("unknown"));
    }

    @Test
    void findByIdMatchesDiscoveredPrinterWhenOneExists() {
        List<Printer> printers = NetworkPrinterDiscovery.discover();
        if (printers.isEmpty()) {
            return;
        }
        Printer expected = printers.get(0);

        assertEquals(Optional.of(expected), NetworkPrinterDiscovery.findById(expected.id()));
    }
}
