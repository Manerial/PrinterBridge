package org.printerbridge.service;

import java.util.List;
import java.util.Optional;
import org.printerbridge.printer.Printer;
import org.printerbridge.service.discovery.*;

final class FakePrinterDiscovery implements PrinterDiscovery {

    private final List<Printer> printers;

    FakePrinterDiscovery(List<Printer> printers) {
        this.printers = printers;
    }

    @Override
    public List<Printer> discover() {
        return printers;
    }

    @Override
    public Optional<Printer> findById(String id) {
        return printers.stream()
                .filter(printer -> printer.id().equals(id))
                .findFirst();
    }
}
