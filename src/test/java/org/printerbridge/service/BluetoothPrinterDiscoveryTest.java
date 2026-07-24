package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fazecast.jSerialComm.SerialPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterType;

class BluetoothPrinterDiscoveryTest {

    private final BluetoothPrinterDiscovery discovery = new BluetoothPrinterDiscovery();

    @Test
    void discoversWhateverSerialPortsAreAvailableWithoutThrowing() {
        List<Printer> printers = assertDoesNotThrow(discovery::discover);

        for (Printer printer : printers) {
            assertNotNull(printer.id());
            assertTrue(printer.id().matches("[0-9a-f]{16}"));
            assertEquals(PrinterType.BLUETOOTH_THERMAL, printer.type());
        }
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertEquals(Optional.empty(), discovery.findById("unknown"));
    }

    // physicalKey is what toPrinter()/findPort() actually hash into a printer id — exercised
    // directly here with constructed maps, independently of any real Bluetooth hardware (see
    // CLAUDE.md: none available). Requested behavior: prefer the MAC (never changes across a
    // re-pair) over the port name (can be reassigned by the OS), falling back to the port name
    // only when no MAC is known for this port.

    @Test
    void physicalKeyPrefersTheWindowsMacOverThePortName() {
        SerialPort port = SerialPort.getCommPort("COM7");
        Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo =
                Map.of("COM7", new WindowsBluetoothPortInfo.PortInfo(true, "Star Thermal Printer", "AABBCCDDEEFF"));

        String key = BluetoothPrinterDiscovery.physicalKey(port, portInfo, Map.of());

        assertEquals("AABBCCDDEEFF", key);
    }

    @Test
    void physicalKeyNormalizesTheLinuxMacToMatchTheWindowsFormat() {
        SerialPort port = SerialPort.getCommPort("rfcomm0");
        Map<String, String> linuxMacs = Map.of("rfcomm0", "AA:BB:CC:DD:EE:FF");

        String key = BluetoothPrinterDiscovery.physicalKey(port, Map.of(), linuxMacs);

        assertEquals("AABBCCDDEEFF", key);
    }

    @Test
    void physicalKeyFallsBackToThePortNameWhenNoMacIsKnown() {
        SerialPort port = SerialPort.getCommPort("COM9");

        String key = BluetoothPrinterDiscovery.physicalKey(port, Map.of(), Map.of());

        assertEquals("COM9", key);
    }

    @Test
    void physicalKeyFallsBackToThePortNameWhenTheWindowsEntryHasNoMac() {
        // The LOCALMFG case (WindowsBluetoothPortInfo.parsePortInfo): a real port entry exists,
        // but it's flagged as not a real remote device and carries no MAC.
        SerialPort port = SerialPort.getCommPort("COM5");
        Map<String, WindowsBluetoothPortInfo.PortInfo> portInfo =
                Map.of("COM5", new WindowsBluetoothPortInfo.PortInfo(false, null, null));

        String key = BluetoothPrinterDiscovery.physicalKey(port, portInfo, Map.of());

        assertEquals("COM5", key);
    }
}
