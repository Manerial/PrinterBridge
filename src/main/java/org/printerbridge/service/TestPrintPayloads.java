package org.printerbridge.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Generates the small, fixed test payloads used by {@link PrintJobService#testPrint(String)} —
 * plain ASCII only (no accents), since an unknown thermal printer's code page can't be assumed.
 */
final class TestPrintPayloads {

    private static final byte[] ESC_INIT = {0x1B, 0x40};
    private static final String TEST_TEXT = "PrinterBridge test print\n\n\n";

    private TestPrintPayloads() {
    }

    static byte[] escPos() {
        byte[] text = TEST_TEXT.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[ESC_INIT.length + text.length];
        System.arraycopy(ESC_INIT, 0, payload, 0, ESC_INIT.length);
        System.arraycopy(text, 0, payload, ESC_INIT.length, text.length);
        return payload;
    }

    static byte[] pdf() {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                stream.newLineAtOffset(50, 700);
                stream.showText("PrinterBridge test print");
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate test PDF", e);
        }
    }
}
