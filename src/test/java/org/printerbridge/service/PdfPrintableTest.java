package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class PdfPrintableTest {

    private static final PageFormat PAGE_FORMAT = new PageFormat();

    @Test
    void printsAGeneratedTestPageWithoutThrowing() throws Exception {
        try (PDDocument document = Loader.loadPDF(TestPrintPayloads.pdf("test-printer"))) {
            Printable printable = new PdfPrintable(document);

            int result = printable.print(newGraphics(), PAGE_FORMAT, 0);

            assertEquals(Printable.PAGE_EXISTS, result);
        }
    }

    @Test
    void returnsNoSuchPageBeyondTheLastPage() throws Exception {
        try (PDDocument document = Loader.loadPDF(TestPrintPayloads.pdf("test-printer"))) {
            Printable printable = new PdfPrintable(document);

            int result = printable.print(newGraphics(), PAGE_FORMAT, 1);

            assertEquals(Printable.NO_SUCH_PAGE, result);
        }
    }

    @Test
    void rejectsAnOversizedPageInsteadOfRenderingIt() throws Exception {
        try (PDDocument document = new PDDocument()) {
            // Past PdfPrintable's own safety bound (200in) — would otherwise attempt to allocate a
            // multi-gigabyte BufferedImage at 300 DPI.
            document.addPage(new PDPage(new PDRectangle(20_000f, 20_000f)));
            Printable printable = new PdfPrintable(document);

            PrinterException exception =
                    assertThrows(PrinterException.class, () -> printable.print(newGraphics(), PAGE_FORMAT, 0));

            assertTrue(exception.getMessage().contains("too large"));
        }
    }

    @Test
    void rejectsADegenerateZeroSizedPage() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(0f, 0f)));
            Printable printable = new PdfPrintable(document);

            PrinterException exception =
                    assertThrows(PrinterException.class, () -> printable.print(newGraphics(), PAGE_FORMAT, 0));

            assertTrue(exception.getMessage().contains("invalid size"));
        }
    }

    @Test
    void rejectsADocumentWithTooManyPages() throws Exception {
        try (PDDocument document = new PDDocument()) {
            // One past PdfPrintable's own safety bound (500) — a real receipt/label/A4 job never
            // needs anywhere near this many pages in a single print request.
            for (int i = 0; i < 501; i++) {
                document.addPage(new PDPage());
            }

            IllegalArgumentException exception =
                    assertThrows(IllegalArgumentException.class, () -> new PdfPrintable(document));

            assertTrue(exception.getMessage().contains("501"));
        }
    }

    private static Graphics2D newGraphics() {
        return new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB).createGraphics();
    }
}
