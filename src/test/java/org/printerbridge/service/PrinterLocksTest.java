package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.Test;

class PrinterLocksTest {

    @Test
    void returnsTheSameLockInstanceForTheSameId() {
        assertSame(PrinterLocks.forPrinter("abc"), PrinterLocks.forPrinter("abc"));
    }

    @Test
    void returnsDifferentLockInstancesForDifferentIds() {
        assertNotSame(PrinterLocks.forPrinter("abc"), PrinterLocks.forPrinter("xyz"));
    }

    @Test
    void serializesConcurrentAccessToTheSamePrinterId() throws InterruptedException {
        String printerId = "shared-printer";
        int threadCount = 8;
        AtomicInteger concurrentExecutions = new AtomicInteger(0);
        AtomicInteger maxConcurrentExecutions = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        Runnable task = () -> {
            try {
                startLatch.await();
                Lock lock = PrinterLocks.forPrinter(printerId);
                lock.lock();
                try {
                    int current = concurrentExecutions.incrementAndGet();
                    maxConcurrentExecutions.updateAndGet(max -> Math.max(max, current));
                    Thread.sleep(20);
                    concurrentExecutions.decrementAndGet();
                } finally {
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        for (int i = 0; i < threadCount; i++) {
            new Thread(task).start();
        }
        startLatch.countDown();

        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, maxConcurrentExecutions.get());
    }

    @Test
    void isNotStuckByDefault() {
        assertFalse(PrinterLocks.isStuck("fresh-id"));
    }

    @Test
    void marksAndClearsAPrinterAsStuck() {
        String printerId = "stuck-printer";

        PrinterLocks.markStuck(printerId);
        assertTrue(PrinterLocks.isStuck(printerId));

        PrinterLocks.clearStuck(printerId);
        assertFalse(PrinterLocks.isStuck(printerId));
    }
}
