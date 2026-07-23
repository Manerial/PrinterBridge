package org.printerbridge.api;

import org.printerbridge.printer.PrintContentType;

record PrintControlMessage(PrintContentType contentType, int size) {
}
