package org.printerbridge.printer;

import java.util.List;
import java.util.stream.Stream;
import org.printerbridge.transport.bluetooth.BluetoothPrinterDiscovery;
import org.printerbridge.transport.network.NetworkPrinterDiscovery;

public final class PrinterRegistry {

    private PrinterRegistry() {
    }

    public static List<Printer> discoverAll() {
        return Stream.concat(
                        BluetoothPrinterDiscovery.discover().stream(),
                        NetworkPrinterDiscovery.discover().stream())
                .toList();
    }
}
