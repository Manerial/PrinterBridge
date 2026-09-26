package org.printerbridge.service.discovery;

import com.fazecast.jSerialComm.SerialPort;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterId;
import org.printerbridge.printer.PrinterStatus;
import org.printerbridge.printer.PrinterType;
import org.printerbridge.service.*;
import org.printerbridge.service.portInfo.WindowsBluetoothPortInfo;
import org.printerbridge.service.portInfo.WindowsBluetoothPortInfo.*;
import org.printerbridge.service.portInfo.LinuxBluetoothPortInfo;
import org.printerbridge.service.portInfo.LinuxBluetoothPortInfo.*;
import org.printerbridge.service.PrinterLocks;

public final class BluetoothPrinterDiscovery implements PrinterDiscovery {

    @Override
    public List<Printer> discover() {
        Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo = WindowsBluetoothPortInfo.query();
        Map<String, String> linuxMacs = LinuxBluetoothPortInfo.queryMacAddresses();
        Map<String, String> linuxFriendlyNames = LinuxBluetoothPortInfo.queryFriendlyNames();
        return Arrays.stream(SerialPort.getCommPorts())
                .filter(port -> isLikelyRealDevice(port, portInfo))
                .map(port -> toPrinter(port, portInfo, linuxMacs, linuxFriendlyNames))
                .toList();
    }

    @Override
    public Optional<Printer> findById(String id) {
        Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo = WindowsBluetoothPortInfo.query();
        Map<String, String> linuxMacs = LinuxBluetoothPortInfo.queryMacAddresses();
        Map<String, String> linuxFriendlyNames = LinuxBluetoothPortInfo.queryFriendlyNames();
        return findPort(id, portInfo, linuxMacs)
                .map(port -> new Printer(id, displayName(port, portInfo, linuxFriendlyNames),
                        PrinterType.BLUETOOTH_THERMAL, testConnectivity(id, port)));
    }

    public static Optional<SerialPort> findPort(String id) {
        return findPort(id, WindowsBluetoothPortInfo.query(), LinuxBluetoothPortInfo.queryMacAddresses());
    }

    private static Optional<SerialPort> findPort(String id, Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo,
            Map<String, String> linuxMacs) {
        return Arrays.stream(SerialPort.getCommPorts())
                .filter(port -> isLikelyRealDevice(port, portInfo))
                .filter(port -> PrinterId.derive(physicalKey(port.getSystemPortName(), portInfo, linuxMacs)).equals(id))
                .findFirst();
    }

    private static boolean isLikelyRealDevice(SerialPort port, Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo) {
        if (LinuxBluetoothPortInfo.isLinux()) {
            // Reliable naming signal on Linux (see LinuxBluetoothPortInfo) — fail closed instead of
            // open, otherwise every serial device on the box would show up as a thermal printer.
            return LinuxBluetoothPortInfo.isLikelyRfcommDevice(port.getSystemPortName());
        }
        // No entry for this port (query failed, or WMI simply doesn't know it) means we can't tell
        // either way — fail open and keep it rather than risk hiding a real printer.
        WindowsBluetoothPortInfo.PortInfo info = portInfo.get(port.getSystemPortName());
        return info == null || info.realRemoteDevice();
    }

    private static Printer toPrinter(SerialPort port, Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo,
            Map<String, String> linuxMacs, Map<String, String> linuxFriendlyNames) {
        String id = PrinterId.derive(physicalKey(port.getSystemPortName(), portInfo, linuxMacs));
        return new Printer(id, displayName(port, portInfo, linuxFriendlyNames), PrinterType.BLUETOOTH_THERMAL,
                PrinterStatus.UNKNOWN);
    }

    /**
     * The value {@link PrinterId} hashes into a printer's id. Prefers the device's Bluetooth MAC —
     * requested explicitly because it never changes, unlike a COM/rfcomm port number which the OS
     * can reassign to the same physical device across a re-pair — and falls back to the system port
     * name only when no MAC is known for this port (query failed, WMI/rfcomm doesn't know this
     * device, or a non-Windows/non-Linux OS). MAC formatting differs by source (Windows: 12 hex
     * chars, no separator; Linux: colon-separated) — normalized here so the same physical address
     * always hashes to the same id regardless of which OS resolved it.
     * <p>Takes the port name as a plain String rather than a {@link SerialPort} — it's all this needs,
     * and {@code SerialPort.getCommPort(String)} validates its argument against the current OS's
     * naming convention (e.g. rejects "COM7" outright on Linux/macOS), which made this untestable
     * with a fabricated port name on any OS other than the one a given test string happened to match.
     */
    public static String physicalKey(String systemPortName, Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo,
                                     Map<String, String> linuxMacs) {
        WindowsBluetoothPortInfo.PortInfo info = portInfo.get(systemPortName);
        if (info != null && info.mac() != null) {
            return normalizeMac(info.mac());
        }
        String linuxMac = linuxMacs.get(systemPortName);
        if (linuxMac != null) {
            return normalizeMac(linuxMac);
        }
        return systemPortName;
    }

    private static String normalizeMac(String mac) {
        return mac.replace(":", "").toUpperCase(Locale.ROOT);
    }

    private static String displayName(SerialPort port, Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo,
            Map<String, String> linuxFriendlyNames) {
        WindowsBluetoothPortInfo.PortInfo info = portInfo.get(port.getSystemPortName());
        if (info != null && info.friendlyName() != null && !info.friendlyName().isBlank()) {
            return info.friendlyName();
        }
        String linuxName = linuxFriendlyNames.get(port.getSystemPortName());
        if (linuxName != null && !linuxName.isBlank()) {
            return linuxName;
        }
        return port.getDescriptivePortName();
    }

    private static PrinterStatus testConnectivity(String id, SerialPort port) {
        Lock lock = PrinterLocks.forPrinter(id);
        if (!lock.tryLock()) {
            // Normally held by an in-flight print job: the connection is obviously alive right now,
            // and we must not compete with it to open the single RFCOMM connection the device allows.
            // Exception: a write that PrintJobService.writeWithTimeout gave up on keeps this lock held
            // until that abandoned write eventually returns on its own — which for a truly dead link
            // may be never. Reporting ONLINE in that case would be a permanent, silent lie.
            return PrinterLocks.isStuck(id) ? PrinterStatus.OFFLINE : PrinterStatus.ONLINE;
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
