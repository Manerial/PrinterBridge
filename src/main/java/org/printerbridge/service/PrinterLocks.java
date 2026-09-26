package org.printerbridge.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock per printer id, so that only one operation at a time ever holds a physical
 * Bluetooth RFCOMM connection open — the protocol itself only allows a single active
 * connection per device (see CLAUDE.md, "Pourquoi ce projet existe").
 */
public final class PrinterLocks {

    private static final ConcurrentHashMap<String, Lock> LOCKS = new ConcurrentHashMap<>();

    // A held lock normally just means "printing right now" (see BluetoothPrinterDiscovery,
    // which reports ONLINE rather than compete for it). That assumption breaks for a write
    // abandoned after PrintJobService.WRITE_TIMEOUT_SECONDS: the lock stays held until that
    // abandoned write eventually finishes on its own, which for a truly dead link may be never.
    // Marking the id here lets a concurrent status check tell "busy printing" apart from
    // "busy because the last write to this link never came back" instead of reporting a
    // permanent, silent false ONLINE.
    private static final Set<String> STUCK = ConcurrentHashMap.newKeySet();

    private PrinterLocks() {
    }

    public static Lock forPrinter(String printerId) {
        return LOCKS.computeIfAbsent(printerId, id -> new ReentrantLock());
    }

    static void markStuck(String printerId) {
        STUCK.add(printerId);
    }

    static void clearStuck(String printerId) {
        STUCK.remove(printerId);
    }

    public static boolean isStuck(String printerId) {
        return STUCK.contains(printerId);
    }
}
