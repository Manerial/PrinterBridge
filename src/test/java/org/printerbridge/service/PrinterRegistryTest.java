package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterStatus;
import org.printerbridge.printer.PrinterType;

class PrinterRegistryTest {

    private final Printer bluetoothPrinter =
            new Printer("bt-1", "Fake BT", PrinterType.BLUETOOTH_THERMAL, PrinterStatus.UNKNOWN);
    private final Printer networkPrinter =
            new Printer("net-1", "Fake Network", PrinterType.NETWORK, PrinterStatus.ONLINE);

    private final PrinterRegistry registry = new PrinterRegistry(List.of(
            new FakePrinterDiscovery(List.of(bluetoothPrinter)),
            new FakePrinterDiscovery(List.of(networkPrinter))));

    @Test
    void discoverAllAggregatesEveryDiscoveryServiceInOrder() {
        assertEquals(List.of(bluetoothPrinter, networkPrinter), registry.discoverAll());
    }

    @Test
    void findStatusReturnsTheFirstMatchAcrossServices() {
        assertEquals(Optional.of(networkPrinter), registry.findStatus("net-1"));
    }

    @Test
    void findStatusReturnsEmptyWhenNoServiceKnowsTheId() {
        assertEquals(Optional.empty(), registry.findStatus("unknown"));
    }
}
