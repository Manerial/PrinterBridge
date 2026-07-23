package org.printerbridge.service;

import java.util.List;
import java.util.stream.Stream;
import org.printerbridge.printer.Printer;

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
