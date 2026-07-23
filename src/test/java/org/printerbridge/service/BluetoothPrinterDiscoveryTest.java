package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterType;

class BluetoothPrinterDiscoveryTest {

    @Test
    void discoversWhateverSerialPortsAreAvailableWithoutThrowing() {
        List<Printer> printers = assertDoesNotThrow(BluetoothPrinterDiscovery::discover);

        for (Printer printer : printers) {
            assertNotNull(printer.id());
            assertTrue(printer.id().matches("[0-9a-f]{16}"));
            assertEquals(PrinterType.BLUETOOTH_THERMAL, printer.type());
        }
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertEquals(Optional.empty(), BluetoothPrinterDiscovery.findById("unknown"));
    }
}
