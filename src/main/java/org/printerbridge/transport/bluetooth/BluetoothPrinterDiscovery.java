package org.printerbridge.transport.bluetooth;

import com.fazecast.jSerialComm.SerialPort;
import java.util.Arrays;
import java.util.List;
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

    private static Printer toPrinter(SerialPort port) {
        String id = PrinterId.derive(port.getSystemPortName());
        return new Printer(id, port.getDescriptivePortName(), PrinterType.BLUETOOTH_THERMAL, PrinterStatus.UNKNOWN);
    }
}
