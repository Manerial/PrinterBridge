package org.printerbridge.transport.network;

import java.util.Arrays;
import java.util.List;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.standard.PrinterIsAcceptingJobs;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterId;
import org.printerbridge.printer.PrinterStatus;
import org.printerbridge.printer.PrinterType;

public final class NetworkPrinterDiscovery {

    private NetworkPrinterDiscovery() {
    }

    public static List<Printer> discover() {
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        return Arrays.stream(services)
                .map(NetworkPrinterDiscovery::toPrinter)
                .toList();
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
