package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterType;
import org.printerbridge.service.discovery.*;
import org.printerbridge.service.portInfo.*;

class BluetoothPrinterDiscoveryTest {

    private final BluetoothPrinterDiscovery discovery = new BluetoothPrinterDiscovery();

    // Both tests below go through SerialPort.getCommPorts() + real WMI/rfcomm enrichment — a
    // ~7.5s external call on Windows (see WindowsBluetoothPortInfo), hence tagged "hardware".

    @Test
    @Tag("hardware")
    void discoversWhateverSerialPortsAreAvailableWithoutThrowing() {
        List<Printer> printers = assertDoesNotThrow(discovery::discover);

        for (Printer printer : printers) {
            assertNotNull(printer.id());
            assertTrue(printer.id().matches("[0-9a-f]{16}"));
            assertEquals(PrinterType.BLUETOOTH_THERMAL, printer.type());
        }
    }

    @Test
    @Tag("hardware")
    void findByIdReturnsEmptyForUnknownId() {
        assertEquals(Optional.empty(), discovery.findById("unknown"));
    }

    // physicalKey prefers the MAC (never changes across a re-pair) over the port name (can be
    // reassigned by the OS), falling back to the port name only when no MAC is known. Takes a plain
    // port name string rather than a real SerialPort on purpose — SerialPort.getCommPort(String)
    // validates against the current OS's naming convention, which broke these tests in CI on
    // non-Windows runners when they used to construct a real SerialPort.

    @Test
    void physicalKeyPrefersTheWindowsMacOverThePortName() {
        Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo =
                Map.of("COM7", new WindowsBluetoothPortInfo.PortInfo(true, "Star Thermal Printer", "AABBCCDDEEFF"));

        String key = BluetoothPrinterDiscovery.physicalKey("COM7", portInfo, Map.of());

        assertEquals("AABBCCDDEEFF", key);
    }

    @Test
    void physicalKeyNormalizesTheLinuxMacToMatchTheWindowsFormat() {
        Map<String, String> linuxMacs = Map.of("rfcomm0", "AA:BB:CC:DD:EE:FF");

        String key = BluetoothPrinterDiscovery.physicalKey("rfcomm0", Map.of(), linuxMacs);

        assertEquals("AABBCCDDEEFF", key);
    }

    @Test
    void physicalKeyFallsBackToThePortNameWhenNoMacIsKnown() {
        String key = BluetoothPrinterDiscovery.physicalKey("COM9", Map.of(), Map.of());

        assertEquals("COM9", key);
    }

    @Test
    void physicalKeyFallsBackToThePortNameWhenTheWindowsEntryHasNoMac() {
        // The LOCALMFG case (WindowsBluetoothPortInfo.parsePortInfo): a real port entry exists,
        // but it's flagged as not a real remote device and carries no MAC.
        Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo =
                Map.of("COM5", new WindowsBluetoothPortInfo.PortInfo(false, null, null));

        String key = BluetoothPrinterDiscovery.physicalKey("COM5", portInfo, Map.of());

        assertEquals("COM5", key);
    }
}
