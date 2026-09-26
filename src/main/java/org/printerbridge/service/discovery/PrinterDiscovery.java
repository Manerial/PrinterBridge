package org.printerbridge.service.discovery;

import java.util.List;
import java.util.Optional;
import org.printerbridge.printer.Printer;

public interface PrinterDiscovery {

    List<Printer> discover();

    Optional<Printer> findById(String id);
}
