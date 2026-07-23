package org.printerbridge.service;

import com.fazecast.jSerialComm.SerialPort;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterId;
import org.printerbridge.printer.PrinterStatus;
import org.printerbridge.printer.PrinterType;

public final class BluetoothPrinterDiscovery implements PrinterDiscovery {

    @Override
    public List<Printer> discover() {
        SerialPort[] ports = SerialPort.getCommPorts();
        return Arrays.stream(ports)
                .map(BluetoothPrinterDiscovery::toPrinter)
                .toList();
    }

    @Override
    public Optional<Printer> findById(String id) {
        return findPort(id)
                .map(port -> new Printer(id, port.getDescriptivePortName(), PrinterType.BLUETOOTH_THERMAL,
                        testConnectivity(id, port)));
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

    private static PrinterStatus testConnectivity(String id, SerialPort port) {
        Lock lock = PrinterLocks.forPrinter(id);
        if (!lock.tryLock()) {
            // Already held by an in-flight print job: the connection is obviously alive right now,
            // and we must not compete with it to open the single RFCOMM connection the device allows.
            return PrinterStatus.ONLINE;
        }
        try {
            if (port.openPort()) {
                port.closePort();
                return PrinterStatus.ONLINE;
            }
            return PrinterStatus.OFFLINE;
        } finally {
            lock.unlock();
        }
    }
}
