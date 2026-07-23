package org.printerbridge.service;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Renders a PDF page-by-page into the {@link Printable} flavor, since Windows' javax.print
 * implementation has no native PDF DocFlavor (unlike CUPS on Mac/Linux) — see CLAUDE.md.
 */
final class PdfPrintable implements Printable {

    private static final int RENDER_DPI = 300;

    private final PDFRenderer renderer;
    private final int pageCount;

    PdfPrintable(PDDocument document) {
        this.renderer = new PDFRenderer(document);
        this.pageCount = document.getNumberOfPages();
    }

    @Override
    public int print(Graphics graphics, PageFormat pageFormat, int pageIndex) throws PrinterException {
        if (pageIndex >= pageCount) {
            return NO_SUCH_PAGE;
        }
        BufferedImage image;
        try {
            image = renderer.renderImageWithDPI(pageIndex, RENDER_DPI);
        } catch (IOException e) {
            throw new PrinterException("Failed to render PDF page " + pageIndex + ": " + e.getMessage());
        }

        Graphics2D g2d = (Graphics2D) graphics;
        g2d.translate(pageFormat.getImageableX(), pageFormat.getImageableY());
        double scale = pageFormat.getImageableWidth() / image.getWidth();
        g2d.scale(scale, scale);
        g2d.drawImage(image, 0, 0, null);
        return PAGE_EXISTS;
    }
}
