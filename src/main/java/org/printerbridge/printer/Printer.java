package org.printerbridge.printer;

public record Printer(String id, String name, PrinterType type, PrinterStatus status) {
}
