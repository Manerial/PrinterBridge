package org.printerbridge.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort disambiguation of Bluetooth SPP ports from other serial devices on Linux, mirroring
 * the intent of {@link WindowsBluetoothPortInfo} but with a different signal since there is no WMI
 * equivalent here: a Bluetooth RFCOMM channel (bound via `rfcomm bind`/BlueZ) shows up as
 * /dev/rfcommN, distinct by name alone from a real UART (ttyS*) or a USB-serial adapter
 * (ttyUSB*, ttyACM*). Unlike the Windows fail-open default (used because a failed/absent WMI query
 * can't be trusted either way), this naming signal is reliable enough to fail closed: a port that
 * isn't rfcommN is filtered out rather than kept as "unknown" — otherwise every serial device on the
 * box (Arduino, modem, real UART...) would show up in GET /printers as a candidate thermal printer.
 * A /dev/rfcommN node only exists once the admin has run `rfcomm bind` for the paired device (unlike
 * Windows, where pairing alone creates the COM port) — an operational step to document, not something
 * PrinterBridge can trigger itself (cf. CLAUDE.md: no active scanning/pairing, only listing).
 * Not validated against a real paired Bluetooth thermal printer — no such hardware available yet
 * (cf. CLAUDE.md).
 */
final class LinuxBluetoothPortInfo {

    private static final Logger LOG = LoggerFactory.getLogger(LinuxBluetoothPortInfo.class);
    private static final Pattern RFCOMM_PORT = Pattern.compile("(?i)^(/dev/)?rfcomm\\d+$");
    private static final Pattern RFCOMM_BINDING = Pattern.compile("^(\\S+):\\s+([0-9A-Fa-f:]{17})\\s+channel");
    private static final Pattern BLUETOOTHCTL_DEVICE = Pattern.compile("^Device\\s+([0-9A-Fa-f:]{17})\\s+(.+)$");
    private static final long COMMAND_TIMEOUT_SECONDS = 5;
    // Same reasoning and TTL as WindowsBluetoothPortInfo's cache: without it, every GET /printers,
    // status check, and print()/testPrint() call would re-run `rfcomm show` + `bluetoothctl devices`.
    private static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static volatile CachedInfo cache;

    private record CachedInfo(long timestampNanos, Map<String, String> macByPort, Map<String, String> friendlyNameByPort) {
    }

    private LinuxBluetoothPortInfo() {
    }

    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    static boolean isLikelyRfcommDevice(String systemPortName) {
        return systemPortName != null && RFCOMM_PORT.matcher(systemPortName).matches();
    }

    /**
     * Maps system port name (e.g. "rfcomm0") to its paired device's MAC address (`rfcomm show`) —
     * used for a stable printer id, since unlike the port name itself the MAC never changes when
     * the device is unpaired/re-paired. Best-effort: any failure (binary missing, no bindings,
     * timeout) yields an empty map rather than hiding a printer — callers must fall back to the
     * port name, same contract as {@link WindowsBluetoothPortInfo}.
     */
    static Map<String, String> queryMacAddresses() {
        return queryInfo().macByPort();
    }

    /**
     * Maps system port name (e.g. "rfcomm0") to a human-readable device name, resolved by cross
     * referencing the port-to-MAC bindings (`rfcomm show`) with the paired-device names BlueZ knows
     * about (`bluetoothctl devices`). Best-effort like the Windows WMI query: any failure (binary
     * missing, no bindings, timeout) yields an empty map rather than hiding a printer — callers must
     * fall back to a generic port name, same contract as {@link WindowsBluetoothPortInfo}.
     */
    static Map<String, String> queryFriendlyNames() {
        return queryInfo().friendlyNameByPort();
    }

    private static CachedInfo queryInfo() {
        if (!isLinux()) {
            return new CachedInfo(0, Map.of(), Map.of());
        }
        CachedInfo cached = cache;
        long now = System.nanoTime();
        if (cached != null && (now - cached.timestampNanos()) < CACHE_TTL_NANOS) {
            return cached;
        }

        CachedInfo queried = queryInfoUncached(now);
        cache = queried;
        return queried;
    }

    private static CachedInfo queryInfoUncached(long now) {
        // rfcomm and bluetoothctl are two separate binaries, so unlike the Windows WMI query
        // (combined into a single PowerShell invocation, see WindowsBluetoothPortInfo) they can't
        // be merged into one process — run them concurrently instead of sequentially so a slow or
        // timed-out one doesn't add its full delay on top of the other's.
        ExecutorService executor = Executors.newFixedThreadPool(2, LinuxBluetoothPortInfo::newDaemonThread);
        try {
            Future<List<String>> rfcommShow = executor.submit(() -> runCommand("rfcomm", "show"));
            Future<List<String>> bluetoothctlDevices = executor.submit(() -> runCommand("bluetoothctl", "devices"));

            // The MAC (rfcomm show) and the friendly name (which also needs bluetoothctl devices)
            // are resolved independently: a `bluetoothctl` failure shouldn't cost us the MAC — and
            // the MAC is what the printer id is now derived from (see BluetoothPrinterDiscovery),
            // so it matters more than the display name ever did.
            Map<String, String> macByPort = safePortToMac(rfcommShow);
            Map<String, String> friendlyNameByPort = safeFriendlyNames(macByPort, bluetoothctlDevices);
            return new CachedInfo(now, macByPort, friendlyNameByPort);
        } finally {
            executor.shutdown();
        }
    }

    private static Map<String, String> safePortToMac(Future<List<String>> rfcommShow) {
        try {
            return parseRfcommBindings(rfcommShow.get());
        } catch (Exception e) {
            LOG.warn("Could not resolve Bluetooth port-to-MAC bindings on Linux (`rfcomm show`); "
                    + "printer ids will fall back to the port name instead of the MAC.", e);
            return Map.of();
        }
    }

    private static Map<String, String> safeFriendlyNames(Map<String, String> macByPort,
            Future<List<String>> bluetoothctlDevices) {
        if (macByPort.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, String> macToName = parseBluetoothctlDevices(bluetoothctlDevices.get());
            Map<String, String> result = new HashMap<>();
            macByPort.forEach((port, mac) -> {
                String name = macToName.get(mac);
                if (name != null && !name.isBlank()) {
                    result.put(port, name);
                }
            });
            return result;
        } catch (Exception e) {
            LOG.warn("Could not resolve Bluetooth device names on Linux (`bluetoothctl devices`); "
                    + "printers will show generic port names.", e);
            return Map.of();
        }
    }

    private static Thread newDaemonThread(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        return thread;
    }

    static Map<String, String> parseRfcommBindings(List<String> lines) {
        Map<String, String> portToMac = new HashMap<>();
        for (String line : lines) {
            Matcher matcher = RFCOMM_BINDING.matcher(line.trim());
            if (matcher.find()) {
                portToMac.put(matcher.group(1), matcher.group(2).toUpperCase(Locale.ROOT));
            }
        }
        return portToMac;
    }

    static Map<String, String> parseBluetoothctlDevices(List<String> lines) {
        Map<String, String> macToName = new HashMap<>();
        for (String line : lines) {
            Matcher matcher = BLUETOOTHCTL_DEVICE.matcher(line.trim());
            if (matcher.find()) {
                macToName.put(matcher.group(1).toUpperCase(Locale.ROOT), matcher.group(2).trim());
            }
        }
        return macToName;
    }

    private static List<String> runCommand(String... command) throws IOException, InterruptedException {
        return ExternalProcess.run(COMMAND_TIMEOUT_SECONDS, command).lines().toList();
    }
}
