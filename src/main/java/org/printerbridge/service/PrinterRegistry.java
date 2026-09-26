package org.printerbridge.service;

import java.util.List;
import java.util.Optional;
import org.printerbridge.printer.Printer;
import org.printerbridge.service.discovery.*;

public final class PrinterRegistry {

    private final List<PrinterDiscovery> discoveryServices;

    public PrinterRegistry() {
        this(List.of(
                new BluetoothPrinterDiscovery(),
                new NetworkPrinterDiscovery())
        );
    }

    public PrinterRegistry(List<PrinterDiscovery> discoveryServices) {
        this.discoveryServices = discoveryServices;
    }

    public List<Printer> discoverAll() {
        return discoveryServices.stream()
                .flatMap(service -> service.discover().stream())
                .toList();
    }

    public Optional<Printer> findStatus(String id) {
        for (PrinterDiscovery service : discoveryServices) {
            Optional<Printer> found = service.findById(id);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
