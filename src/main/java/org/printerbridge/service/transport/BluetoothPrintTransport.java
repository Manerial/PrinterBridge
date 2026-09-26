package org.printerbridge.service.transport;

import com.fazecast.jSerialComm.SerialPort;
import java.io.IOException;

/**
 * Platform-specific way to actually get bytes to a Bluetooth SPP printer. {@code PrintJobService}
 * (org.printerbridge.service) owns everything that doesn't differ by OS — locking, the bounded
 * write timeout, the settle delay — and only delegates the raw open/write/close here.
 */
public interface BluetoothPrintTransport {

    Channel open(SerialPort port) throws IOException;

    interface Channel {
        void write(byte[] payload) throws IOException;

        /** Never throws: by the time this is called, the write already either succeeded, failed, or
         * timed out, and there's nothing further the caller can do with a close failure. */
        void close();
    }
}
