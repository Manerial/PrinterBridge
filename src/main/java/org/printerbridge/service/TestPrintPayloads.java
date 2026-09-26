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

    private TestPrintPayloads() {
    }

    private static final String TEST_TEXT = "PrinterBridge test print";

    // Le nombre de sauts de ligne finaux n'est pas cosmétique : sur une imprimante thermique, le
    // texte peut être réellement imprimé sur le rouleau sans que le papier avance assez pour
    // dépasser la fente/le massicot — donnant l'impression que rien ne s'est passé alors que
    // plusieurs tests successifs s'accumulent en fait sur le même rouleau, invisibles jusqu'à ce
    // qu'une avance suffisante (ou cumulée) les pousse enfin dehors. Vérifié empiriquement sur un
    // modèle Netum (cf. CLAUDE.md) : 0 saut de ligne final ne suffit pas, 3 suffisent déjà — 4 ici
    // pour une petite marge sans gâcher de papier à chaque test.
    private static final int TRAILING_FEED_LINES = 4;

    static byte[] escPos(String printerId) {
        String trailingFeed = "\n".repeat(TRAILING_FEED_LINES);
        byte[] text = (TEST_TEXT + "\n" + printerId + trailingFeed).getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[ESC_INIT.length + text.length];
        System.arraycopy(ESC_INIT, 0, payload, 0, ESC_INIT.length);
        System.arraycopy(text, 0, payload, ESC_INIT.length, text.length);
        return payload;
    }

    static byte[] pdf(String printerId) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                stream.newLineAtOffset(50, 700);
                stream.showText(TEST_TEXT);
                stream.newLineAtOffset(0, -32); // saute une ligne vide avant l'id
                stream.showText(printerId);
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
