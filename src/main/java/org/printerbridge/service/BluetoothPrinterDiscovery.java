package org.printerbridge.service;

import com.fazecast.jSerialComm.SerialPort;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterId;
import org.printerbridge.printer.PrinterStatus;
import org.printerbridge.printer.PrinterType;

public final class BluetoothPrinterDiscovery {

    private BluetoothPrinterDiscovery() {
    }

    public static List<Printer> discover() {
        SerialPort[] ports = SerialPort.getCommPorts();
        return Arrays.stream(ports)
                .map(BluetoothPrinterDiscovery::toPrinter)
                .toList();
    }

    public static Optional<Printer> findById(String id) {
        return findPort(id)
                .map(port -> new Printer(id, port.getDescriptivePortName(), PrinterType.BLUETOOTH_THERMAL,
                        testConnectivity(port)));
    }

    static Optional<SerialPort> findPort(String id) {
        return Arrays.stream(SerialPort.getCommPorts())
                .filter(port -> PrinterId.derive(port.getSystemPortName()).equals(id))
                .findFirst();
    }

    private static Printer toPrinter(SerialPort port) {
        String id = PrinterId.derive(port.getSystemPortName());
        return new Printer(id, port.getDescriptivePortName(), PrinterType.BLUETOOTH_THERMAL, PrinterStatus.UNKNOWN);
    }

    private static PrinterStatus testConnectivity(SerialPort port) {
        if (port.openPort()) {
            port.closePort();
            return PrinterStatus.ONLINE;
        }
        return PrinterStatus.OFFLINE;
    }
}
