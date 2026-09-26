package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.printerbridge.service.portInfo.*;

class ExternalProcessTest {

    private static final String JAVA_BINARY =
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    private static final String TEST_CLASSPATH = System.getProperty("java.class.path");

    @Test
    void runsACommandAndReturnsItsOutput() throws Exception {
        String output = ExternalProcess.run(10, JAVA_BINARY, "-version");

        assertTrue(output.toLowerCase().contains("version"));
    }

    @Test
    void enforcesTheTimeoutInsteadOfHangingOnAProcessThatNeverExits() {
        long start = System.nanoTime();

        IOException exception = assertThrows(IOException.class, () -> ExternalProcess.run(1,
                JAVA_BINARY, "-cp", TEST_CLASSPATH, SleepForeverMain.class.getName()));

        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertTrue(exception.getMessage().contains("timed out"));
        assertTrue(elapsedSeconds < 10, "should time out quickly rather than hang for the child's full lifetime");
    }
}
