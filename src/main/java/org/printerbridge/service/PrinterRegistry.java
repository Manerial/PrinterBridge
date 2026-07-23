package org.printerbridge.service;

import java.util.List;
import java.util.Optional;
import org.printerbridge.printer.Printer;

public final class PrinterRegistry {

    private static final List<PrinterDiscovery> DISCOVERY_SERVICES = List.of(
            new BluetoothPrinterDiscovery(),
            new NetworkPrinterDiscovery());

    private PrinterRegistry() {
    }

    public static List<Printer> discoverAll() {
        return DISCOVERY_SERVICES.stream()
                .flatMap(service -> service.discover().stream())
                .toList();
    }

    public static Optional<Printer> findStatus(String id) {
        for (PrinterDiscovery service : DISCOVERY_SERVICES) {
            Optional<Printer> found = service.findById(id);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
