package org.printerbridge.service.transport;

import com.fazecast.jSerialComm.SerialPort;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Confirmed on real hardware (Netum thermal printer, cf. CLAUDE.md): jSerialComm's own
 * open()/write()/close() never gets data to this printer on Linux, while a plain byte-for-byte
 * write to the /dev/rfcommN device node (what a shell {@code > /dev/rfcommN} redirection does)
 * works reliably. Kept separate from {@link WindowsBluetoothPrintTransport} so this can keep
 * being iterated on (e.g. a connection kept alive across jobs) without risking the Windows path.
 */
public final class LinuxBluetoothPrintTransport implements BluetoothPrintTransport {

    @Override
    public Channel open(SerialPort port) throws IOException {
        File devicePath = new File("/dev", port.getSystemPortName());
        FileOutputStream out = new FileOutputStream(devicePath);
        return new Channel() {
            @Override
            public void write(byte[] payload) throws IOException {
                out.write(payload);
                out.flush();
            }

            @Override
            public void close() {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Best effort: the write already failed or timed out by this point, closing is
                    // just releasing the RFCOMM connection, not something the caller can act on.
                }
            }
        };
    }
}
