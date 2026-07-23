package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PrinterRegistryTest {

    @Test
    void aggregatesBluetoothAndNetworkDiscovery() {
        long expectedCount = Stream.concat(
                        BluetoothPrinterDiscovery.discover().stream(),
                        NetworkPrinterDiscovery.discover().stream())
                .count();

        assertEquals(expectedCount, PrinterRegistry.discoverAll().size());
    }
}
