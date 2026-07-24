package org.printerbridge.service;

/**
 * Thrown when a printer id doesn't correspond to any currently discovered printer — distinct from
 * other {@link PrintJobException} cases (busy, hardware/PDF failure, content/transport mismatch)
 * so HTTP callers can map it to 404, consistent with {@code GET /printers/{id}/status}.
 */
public final class UnknownPrinterException extends PrintJobException {

    public UnknownPrinterException(String printerId) {
        super("Unknown printer id: " + printerId);
    }
}
