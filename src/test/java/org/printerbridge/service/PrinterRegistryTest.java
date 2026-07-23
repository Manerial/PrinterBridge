package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.printerbridge.printer.Printer;

class PrinterRegistryTest {

    @Test
    void aggregatesBluetoothAndNetworkDiscovery() {
        long expectedCount = Stream.concat(
                        BluetoothPrinterDiscovery.discover().stream(),
                        NetworkPrinterDiscovery.discover().stream())
                .count();

        assertEquals(expectedCount, PrinterRegistry.discoverAll().size());
    }

    @Test
    void findStatusReturnsEmptyForUnknownId() {
        assertEquals(Optional.empty(), PrinterRegistry.findStatus("unknown"));
    }

    @Test
    void findStatusMatchesADiscoveredPrinterWhenOneExists() {
        List<Printer> printers = PrinterRegistry.discoverAll();
        if (printers.isEmpty()) {
            return;
        }
        Printer expected = printers.get(0);

        assertTrue(PrinterRegistry.findStatus(expected.id()).isPresent());
        assertEquals(expected.id(), PrinterRegistry.findStatus(expected.id()).get().id());
    }
}
