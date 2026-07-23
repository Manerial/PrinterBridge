package org.printerbridge.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock per printer id, so that only one operation at a time ever holds a physical
 * Bluetooth RFCOMM connection open — the protocol itself only allows a single active
 * connection per device (see CLAUDE.md, "Pourquoi ce projet existe").
 */
final class PrinterLocks {

    private static final ConcurrentHashMap<String, Lock> LOCKS = new ConcurrentHashMap<>();

    private PrinterLocks() {
    }

    static Lock forPrinter(String printerId) {
        return LOCKS.computeIfAbsent(printerId, id -> new ReentrantLock());
    }
}
