package org.printerbridge.printer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class PrinterIdTest {

    @Test
    void sameKeyAlwaysProducesSameId() {
        assertEquals(PrinterId.derive("HP LaserJet M15w"), PrinterId.derive("HP LaserJet M15w"));
    }

    @Test
    void differentKeysProduceDifferentIds() {
        assertNotEquals(PrinterId.derive("HP LaserJet M15w"), PrinterId.derive("Brother HL-L2350DW"));
    }
}
