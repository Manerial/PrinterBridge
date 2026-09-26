package org.printerbridge.service.transport;

import com.fazecast.jSerialComm.SerialPort;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bypasses jSerialComm on Linux: its open()/write()/close() never gets data to this printer,
 * while a plain write to /dev/rfcommN (what a shell redirect does) works (cf. CLAUDE.md).
 *
 * <p>Keeps the connection open across jobs instead of reopening per job: a cold open can take
 * ~30s (Bluetooth wake-up) while reusing a recent one is near-instant. {@link Channel#close()} is
 * a no-op; only a failed write drops the connection so the next job reopens fresh.
 */
public final class LinuxBluetoothPrintTransport implements BluetoothPrintTransport {

    private static final ConcurrentMap<String, FileOutputStream> OPEN_STREAMS = new ConcurrentHashMap<>();

    @Override
    public Channel open(SerialPort port) throws IOException {
        String portName = port.getSystemPortName();
        FileOutputStream stream = OPEN_STREAMS.get(portName);
        if (stream == null) {
            stream = new FileOutputStream(new File("/dev", portName));
            OPEN_STREAMS.put(portName, stream);
        }
        FileOutputStream openStream = stream;
        return new Channel() {
            @Override
            public void write(byte[] payload) throws IOException {
                try {
                    openStream.write(payload);
                    openStream.flush();
                } catch (IOException e) {
                    // The connection is dead; drop it so the next job reopens fresh instead of
                    // repeatedly failing against a stream that will never recover.
                    OPEN_STREAMS.remove(portName, openStream);
                    closeQuietly(openStream);
                    throw e;
                }
            }

            @Override
            public void close() {
                // Deliberately not closed here: kept open across jobs, see class javadoc.
            }
        };
    }

    private static void closeQuietly(FileOutputStream out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
