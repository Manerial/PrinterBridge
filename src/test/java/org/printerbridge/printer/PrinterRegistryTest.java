package org.printerbridge.printer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.printerbridge.transport.bluetooth.BluetoothPrinterDiscovery;
import org.printerbridge.transport.network.NetworkPrinterDiscovery;

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
