package org.printerbridge.transport.network;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterType;

class NetworkPrinterDiscoveryTest {

    @Test
    void discoversWhateverIsRegisteredAtOsLevelWithoutThrowing() {
        List<Printer> printers = assertDoesNotThrow(NetworkPrinterDiscovery::discover);

        for (Printer printer : printers) {
            assertNotNull(printer.id());
            assertTrue(printer.id().matches("[0-9a-f]{16}"));
            assertEquals(PrinterType.NETWORK, printer.type());
        }
    }
}
