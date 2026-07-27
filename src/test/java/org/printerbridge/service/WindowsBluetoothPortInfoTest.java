package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class WindowsBluetoothPortInfoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Real WMI call — ~7.5s measured on Windows (the enumeration itself, not a code bug). Tagged
    // "hardware", excluded from the default `mvn test` run (pom.xml, test.excludedGroups).
    @Test
    @Tag("hardware")
    void queryNeverThrowsAndReturnsAUsableMap() {
        Map<String, WindowsBluetoothPortInfo.PortInfo> result = assertDoesNotThrow(WindowsBluetoothPortInfo::query);
        assertNotNull(result);
    }

    @Test
    void parsesBluetoothDeviceNamesFromInstanceId() {
        ArrayNode devices = MAPPER.createArrayNode();
        devices.addObject()
                .put("InstanceId", "BTHENUM-DEV_aabbccddeeff-7&1234&0&0000")
                .put("FriendlyName", "Star Thermal Printer");

        Map<String, String> result = WindowsBluetoothPortInfo.parseBluetoothDeviceNames(devices);

        assertEquals(Map.of("AABBCCDDEEFF", "Star Thermal Printer"), result);
    }

    @Test
    void parsePortInfoFlagsLocalmfgAsNotARealDevice() {
        ArrayNode ports = MAPPER.createArrayNode();
        ports.addObject()
                .put("DeviceID", "COM5")
                .put("PNPDeviceID", "BTHENUM-{0000110E}_LOCALMFG&0000-7&1234&0&0000");

        Map<String, WindowsBluetoothPortInfo.PortInfo> result = WindowsBluetoothPortInfo.parsePortInfo(ports, Map.of());

        assertEquals(new WindowsBluetoothPortInfo.PortInfo(false, null, null), result.get("COM5"));
    }

    @Test
    void parsePortInfoMatchesRealDeviceByMacAndEnrichesWithFriendlyName() {
        ArrayNode ports = MAPPER.createArrayNode();
        ports.addObject()
                .put("DeviceID", "COM7")
                .put("PNPDeviceID", "BTHENUM-{0000110E}-7&1234&0&aabbccddeeff_0000");

        Map<String, WindowsBluetoothPortInfo.PortInfo> result = WindowsBluetoothPortInfo.parsePortInfo(
                ports, Map.of("AABBCCDDEEFF", "Star Thermal Printer"));

        assertEquals(new WindowsBluetoothPortInfo.PortInfo(true, "Star Thermal Printer", "AABBCCDDEEFF"), result.get("COM7"));
    }

    @Test
    void parsePortInfoSkipsPortsWithoutADeviceId() {
        ArrayNode ports = MAPPER.createArrayNode();
        ports.addObject().put("DeviceID", "").put("PNPDeviceID", "aabbccddeeff_0000");

        Map<String, WindowsBluetoothPortInfo.PortInfo> result = WindowsBluetoothPortInfo.parsePortInfo(ports, Map.of());

        assertEquals(Map.of(), result);
    }
}
