package org.printerbridge.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short-lived external command with an actually-enforced timeout. A naive
 * {@code in.readAllBytes()} before {@code process.waitFor(timeout)} blocks on the read itself if
 * the child never closes its stdout (hung process, waiting on a dependency that isn't running),
 * making the timeout unreachable dead code. Fixed here by draining stdout on a background thread
 * while the timeout is enforced on the calling thread; timing out force-destroys the process,
 * which closes the pipe and unblocks the reader.
 */
final class ExternalProcess {

    private ExternalProcess() {
    }

    static String run(long timeoutSeconds, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                output.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Stream closed out from under us by a forced destroy on timeout; the caller
                // reports a timeout regardless of whatever partial output was captured.
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(TimeUnit.SECONDS.toMillis(1));
            throw new IOException(command[0] + " timed out");
        }
        // The process exiting doesn't guarantee the reader thread is done draining stdout (e.g. a
        // lot of buffered output, or a detached grandchild still holding the pipe open) — reading
        // `output` before the reader thread has actually finished would race a plain StringBuilder
        // append from another thread. isAlive() after join() tells us which case we're in instead
        // of silently returning a possibly-torn read.
        reader.join(TimeUnit.SECONDS.toMillis(1));
        if (reader.isAlive()) {
            throw new IOException(command[0] + " exited but its output stream did not close in time");
        }
        return output.toString();
    }
}
