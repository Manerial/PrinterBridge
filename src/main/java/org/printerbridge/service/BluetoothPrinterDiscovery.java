package org.printerbridge.service;

import com.fazecast.jSerialComm.SerialPort;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterId;
import org.printerbridge.printer.PrinterStatus;
import org.printerbridge.printer.PrinterType;

public final class BluetoothPrinterDiscovery implements PrinterDiscovery {

    @Override
    public List<Printer> discover() {
        Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo = WindowsBluetoothPortInfo.query();
        return Arrays.stream(SerialPort.getCommPorts())
                .filter(port -> isLikelyRealDevice(port, portInfo))
                .map(port -> toPrinter(port, portInfo))
                .toList();
    }

    @Override
    public Optional<Printer> findById(String id) {
        Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo = WindowsBluetoothPortInfo.query();
        return findPort(id, portInfo)
                .map(port -> new Printer(id, displayName(port, portInfo), PrinterType.BLUETOOTH_THERMAL,
                        testConnectivity(id, port)));
    }

    static Optional<SerialPort> findPort(String id) {
        return findPort(id, WindowsBluetoothPortInfo.query());
    }

    private static Optional<SerialPort> findPort(String id, Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo) {
        return Arrays.stream(SerialPort.getCommPorts())
                .filter(port -> isLikelyRealDevice(port, portInfo))
                .filter(port -> PrinterId.derive(port.getSystemPortName()).equals(id))
                .findFirst();
    }

    private static boolean isLikelyRealDevice(SerialPort port, Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo) {
        // No entry for this port (non-Windows, query failed, or WMI simply doesn't know it) means
        // we can't tell either way — fail open and keep it rather than risk hiding a real printer.
        WindowsBluetoothPortInfo.PortInfo info = portInfo.get(port.getSystemPortName());
        return info == null || info.realRemoteDevice();
    }

    private static Printer toPrinter(SerialPort port, Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo) {
        String id = PrinterId.derive(port.getSystemPortName());
        return new Printer(id, displayName(port, portInfo), PrinterType.BLUETOOTH_THERMAL, PrinterStatus.UNKNOWN);
    }

    private static String displayName(SerialPort port, Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo) {
        WindowsBluetoothPortInfo.PortInfo info = portInfo.get(port.getSystemPortName());
        if (info != null && info.friendlyName() != null && !info.friendlyName().isBlank()) {
            return info.friendlyName();
        }
        return port.getDescriptivePortName();
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
