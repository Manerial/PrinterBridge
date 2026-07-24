package org.printerbridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort disambiguation of "real" Bluetooth SPP devices from the generic/local serial-port
 * artifacts jSerialComm can't tell apart on its own (cf. CLAUDE.md). Confirmed against real
 * hardware: a genuine paired device's PNPDeviceID carries its MAC address, while non-device
 * artifacts (e.g. a local loopback SPP channel) carry "LOCALMFG" instead. Windows-only, backed by
 * WMI via PowerShell; fails open (returns no info at all) on any error, so a query failure never
 * hides a real printer — callers must treat "no entry for this port" as "keep it, unknown".
 * No Linux/macOS equivalent exists yet.
 */
final class WindowsBluetoothPortInfo {

    private static final Logger LOG = LoggerFactory.getLogger(WindowsBluetoothPortInfo.class);
    private static final Pattern MAC_BEFORE_SUFFIX = Pattern.compile("([0-9A-Fa-f]{12})_[^\\\\]*$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Une seule invocation de powershell.exe pour les deux requêtes WMI : deux process séparés
    // doublaient le coût de démarrage du moteur PowerShell (le vrai coût, bien avant celui de la
    // requête WMI elle-même), ce qui rendait GET /printers perceptiblement lent.
    private static final String COMBINED_QUERY_SCRIPT =
            "$devices = Get-PnpDevice -Class Bluetooth | Select-Object FriendlyName, InstanceId; "
            + "$ports = Get-CimInstance Win32_SerialPort | Select-Object DeviceID, PNPDeviceID; "
            + "[PSCustomObject]@{ Devices = $devices; Ports = $ports } | ConvertTo-Json -Depth 4";

    record PortInfo(boolean realRemoteDevice, String friendlyName) {
    }

    private WindowsBluetoothPortInfo() {
    }

    static Map<String, PortInfo> query() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return Map.of();
        }
        try {
            JsonNode result = runPowerShellJson(COMBINED_QUERY_SCRIPT);
            Map<String, String> macToFriendlyName = parseBluetoothDeviceNames(result.path("Devices"));
            return parsePortInfo(result.path("Ports"), macToFriendlyName);
        } catch (Exception e) {
            LOG.warn("Could not query Windows Bluetooth port info; no filtering/enrichment will be applied.", e);
            return Map.of();
        }
    }

    private static Map<String, String> parseBluetoothDeviceNames(JsonNode devices) {
        Map<String, String> macToName = new HashMap<>();
        for (JsonNode device : asArray(devices)) {
            String instanceId = device.path("InstanceId").asText("");
            String friendlyName = device.path("FriendlyName").asText("");
            int devIndex = instanceId.indexOf("DEV_");
            if (devIndex >= 0 && instanceId.length() >= devIndex + 16) {
                String mac = instanceId.substring(devIndex + 4, devIndex + 16);
                macToName.putIfAbsent(mac.toUpperCase(Locale.ROOT), friendlyName);
            }
        }
        return macToName;
    }

    private static Map<String, PortInfo> parsePortInfo(JsonNode ports, Map<String, String> macToFriendlyName) {
        Map<String, PortInfo> result = new HashMap<>();
        for (JsonNode port : asArray(ports)) {
            String deviceId = port.path("DeviceID").asText("");
            String pnpDeviceId = port.path("PNPDeviceID").asText("");
            if (deviceId.isEmpty()) {
                continue;
            }
            if (pnpDeviceId.contains("LOCALMFG")) {
                result.put(deviceId, new PortInfo(false, null));
                continue;
            }
            Matcher matcher = MAC_BEFORE_SUFFIX.matcher(pnpDeviceId);
            if (matcher.find()) {
                String mac = matcher.group(1).toUpperCase(Locale.ROOT);
                result.put(deviceId, new PortInfo(true, macToFriendlyName.get(mac)));
            }
        }
        return result;
    }

    private static List<JsonNode> asArray(JsonNode node) {
        if (node.isArray()) {
            List<JsonNode> list = new ArrayList<>();
            node.forEach(list::add);
            return list;
        }
        if (node.isObject()) {
            return List.of(node);
        }
        return List.of();
    }

    private static JsonNode runPowerShellJson(String command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", command);
        Process process = builder.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("PowerShell query timed out");
        }
        if (output.isBlank()) {
            return MAPPER.createArrayNode();
        }
        return MAPPER.readTree(output);
    }
}
