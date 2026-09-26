package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.printerbridge.service.portInfo.*;

class LinuxBluetoothPortInfoTest {

    @Test
    void acceptsRfcommPorts() {
        assertTrue(LinuxBluetoothPortInfo.isLikelyRfcommDevice("rfcomm0"));
        assertTrue(LinuxBluetoothPortInfo.isLikelyRfcommDevice("/dev/rfcomm12"));
        assertTrue(LinuxBluetoothPortInfo.isLikelyRfcommDevice("RFCOMM3"));
    }

    @Test
    void rejectsNonRfcommSerialPorts() {
        assertFalse(LinuxBluetoothPortInfo.isLikelyRfcommDevice("ttyS0"));
        assertFalse(LinuxBluetoothPortInfo.isLikelyRfcommDevice("ttyUSB0"));
        assertFalse(LinuxBluetoothPortInfo.isLikelyRfcommDevice("ttyACM0"));
        assertFalse(LinuxBluetoothPortInfo.isLikelyRfcommDevice("COM3"));
        assertFalse(LinuxBluetoothPortInfo.isLikelyRfcommDevice(null));
    }

    @Test
    void parsesRfcommShowOutput() {
        List<String> lines = List.of(
                "rfcomm0: 00:11:22:33:44:55 channel 1 clean",
                "rfcomm1: aa:bb:cc:dd:ee:ff channel 2 clean",
                "",
                "not a binding line");

        Map<String, String> result = LinuxBluetoothPortInfo.parseRfcommBindings(lines);

        assertEquals(Map.of("rfcomm0", "00:11:22:33:44:55", "rfcomm1", "AA:BB:CC:DD:EE:FF"), result);
    }

    @Test
    void parsesBluetoothctlDevicesOutput() {
        List<String> lines = List.of(
                "Device 00:11:22:33:44:55 Star Thermal Printer",
                "Device aa:bb:cc:dd:ee:ff Some Other Device",
                "",
                "not a device line");

        Map<String, String> result = LinuxBluetoothPortInfo.parseBluetoothctlDevices(lines);

        assertEquals(Map.of(
                "00:11:22:33:44:55", "Star Thermal Printer",
                "AA:BB:CC:DD:EE:FF", "Some Other Device"), result);
    }

    @Test
    void queryFriendlyNamesNeverThrowsAndReturnsAUsableMap() {
        // Unlike an `if (!isLinux()) { assert ... }` guard, this runs the same assertion on every
        // platform: on non-Linux it exercises the early-return path, and on Linux — the actual
        // deployment target — it exercises the real rfcomm/bluetoothctl invocation and its
        // fail-safe empty-map fallback if those binaries aren't available on this machine.
        Map<String, String> result = assertDoesNotThrow(LinuxBluetoothPortInfo::queryFriendlyNames);
        assertNotNull(result);
    }

    @Test
    void queryMacAddressesNeverThrowsAndReturnsAUsableMap() {
        // Same reasoning as queryFriendlyNamesNeverThrowsAndReturnsAUsableMap — this is what
        // BluetoothPrinterDiscovery.physicalKey now relies on for a stable printer id.
        Map<String, String> result = assertDoesNotThrow(LinuxBluetoothPortInfo::queryMacAddresses);
        assertNotNull(result);
    }
}
