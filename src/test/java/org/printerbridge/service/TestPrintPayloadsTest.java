package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TestPrintPayloadsTest {

    @Test
    void escPosStartsWithInitSequenceAndContainsTheTestText() {
        byte[] payload = TestPrintPayloads.escPos();

        assertArrayEquals(new byte[]{0x1B, 0x40}, Arrays.copyOfRange(payload, 0, 2));
        String text = new String(payload, 2, payload.length - 2, StandardCharsets.US_ASCII);
        assertTrue(text.contains("PrinterBridge test print"));
    }

    @Test
    void pdfProducesAValidLookingPdfDocument() {
        byte[] pdf = TestPrintPayloads.pdf();

        assertTrue(pdf.length > 0);
        assertArrayEquals("%PDF-".getBytes(StandardCharsets.US_ASCII), Arrays.copyOfRange(pdf, 0, 5));
    }
}
