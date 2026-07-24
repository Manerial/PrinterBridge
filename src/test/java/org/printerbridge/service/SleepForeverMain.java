package org.printerbridge.service;

/**
 * Test-only helper process for {@link ExternalProcessTest}: launched via the same {@code java}
 * binary running the tests, so the timeout-enforcement path in {@link ExternalProcess} can be
 * exercised against a real child process that never exits, portably across OSes.
 */
public final class SleepForeverMain {

    private SleepForeverMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(Long.MAX_VALUE);
    }
}
