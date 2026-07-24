package org.printerbridge.service;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Renders a PDF page-by-page into the {@link Printable} flavor, since Windows' javax.print
 * implementation has no native PDF DocFlavor (unlike CUPS on Mac/Linux) — see CLAUDE.md.
 */
final class PdfPrintable implements Printable {

    private static final int RENDER_DPI = 300;

    // Adobe's own long-standing PDF page-size limit (200 inches). A page past this is either a
    // malformed/hostile PDF or, at RENDER_DPI, would make renderImageWithDPI allocate a
    // multi-gigabyte BufferedImage — well beyond anything a real receipt/A4 label needs.
    private static final float MAX_PAGE_DIMENSION_POINTS = 14_400f;

    // Bounds worst-case per-job rendering cost: even under MAX_PAGE_DIMENSION_POINTS, a PDF with
    // thousands of small pages still means thousands of 300 DPI renders submitted back-to-back to
    // a physical printer. No real receipt/label/A4 job needs anywhere near this many pages.
    private static final int MAX_PAGE_COUNT = 500;

    private final PDDocument document;
    private final PDFRenderer renderer;
    private final int pageCount;

    PdfPrintable(PDDocument document) {
        this.pageCount = document.getNumberOfPages();
        if (pageCount > MAX_PAGE_COUNT) {
            throw new IllegalArgumentException(
                    "PDF has " + pageCount + " pages, exceeding the safety limit of " + MAX_PAGE_COUNT);
        }
        this.document = document;
        this.renderer = new PDFRenderer(document);
    }

    @Override
    public int print(Graphics graphics, PageFormat pageFormat, int pageIndex) throws PrinterException {
        if (pageIndex >= pageCount) {
            return NO_SUCH_PAGE;
        }

        PDRectangle mediaBox = document.getPage(pageIndex).getMediaBox();
        if (mediaBox.getWidth() <= 0 || mediaBox.getHeight() <= 0) {
            throw new PrinterException("PDF page " + pageIndex + " has an invalid size ("
                    + mediaBox.getWidth() + "x" + mediaBox.getHeight() + " pt)");
        }
        if (mediaBox.getWidth() > MAX_PAGE_DIMENSION_POINTS || mediaBox.getHeight() > MAX_PAGE_DIMENSION_POINTS) {
            throw new PrinterException("PDF page " + pageIndex + " is too large to render safely ("
                    + mediaBox.getWidth() + "x" + mediaBox.getHeight() + " pt)");
        }

        BufferedImage image;
        try {
            image = renderer.renderImageWithDPI(pageIndex, RENDER_DPI);
        } catch (IOException e) {
            throw new PrinterException("Failed to render PDF page " + pageIndex + ": " + e.getMessage());
        }

        Graphics2D g2d = (Graphics2D) graphics;
        g2d.translate(pageFormat.getImageableX(), pageFormat.getImageableY());
        // Scaled independently on each axis and clamped to the smaller ratio (aspect-ratio
        // preserving "fit"): a width-only scale (the previous behavior) let any page whose aspect
        // ratio didn't match the imageable area overflow past its bottom edge or get cropped.
        double scaleX = pageFormat.getImageableWidth() / image.getWidth();
        double scaleY = pageFormat.getImageableHeight() / image.getHeight();
        double scale = Math.min(scaleX, scaleY);
        g2d.scale(scale, scale);
        g2d.drawImage(image, 0, 0, null);
        return PAGE_EXISTS;
    }
}
