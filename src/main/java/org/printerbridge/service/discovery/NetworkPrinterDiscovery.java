package org.printerbridge.service.discovery;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.standard.PrinterIsAcceptingJobs;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterId;
import org.printerbridge.printer.PrinterStatus;
import org.printerbridge.printer.PrinterType;

public final class NetworkPrinterDiscovery implements PrinterDiscovery {

    @Override
    public List<Printer> discover() {
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        return Arrays.stream(services)
                .map(NetworkPrinterDiscovery::toPrinter)
                .toList();
    }

    @Override
    public Optional<Printer> findById(String id) {
        return discover().stream()
                .filter(printer -> printer.id().equals(id))
                .findFirst();
    }

    public static Optional<PrintService> findService(String id) {
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .filter(service -> PrinterId.derive(service.getName()).equals(id))
                .findFirst();
    }

    private static Printer toPrinter(PrintService service) {
        String name = service.getName();
        String id = PrinterId.derive(name);
        return new Printer(id, name, PrinterType.NETWORK, statusOf(service));
    }

    private static PrinterStatus statusOf(PrintService service) {
        PrinterIsAcceptingJobs accepting = service.getAttribute(PrinterIsAcceptingJobs.class);
        if (accepting == null) {
            return PrinterStatus.UNKNOWN;
        }
        return accepting == PrinterIsAcceptingJobs.ACCEPTING_JOBS ? PrinterStatus.ONLINE : PrinterStatus.OFFLINE;
    }
}
