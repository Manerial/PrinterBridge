package org.printerbridge.service.transport;

import com.fazecast.jSerialComm.SerialPort;
import java.io.IOException;

/**
 * Unchanged from the original jSerialComm-based implementation — already validated end to end on
 * real Windows hardware (cf. CLAUDE.md). Do not modify without re-validating on Windows.
 */
public final class WindowsBluetoothPrintTransport implements BluetoothPrintTransport {

    @Override
    public Channel open(SerialPort port) throws IOException {
        if (!port.openPort()) {
            throw new IOException("Could not open Bluetooth port " + port.getSystemPortName());
        }
        return new Channel() {
            @Override
            public void write(byte[] payload) throws IOException {
                port.getOutputStream().write(payload);
                port.getOutputStream().flush();
            }

            @Override
            public void close() {
                port.closePort();
            }
        };
    }
}
