package org.printerbridge.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WindowsBluetoothPortInfoTest {

    @Test
    void queryNeverThrowsAndReturnsAUsableMap() {
        Map<String, WindowsBluetoothPortInfo.PortInfo> result = assertDoesNotThrow(WindowsBluetoothPortInfo::query);
        assertNotNull(result);

        // Manual inspection aid while validating against real hardware; not an assertion.
        result.forEach((port, info) ->
                System.out.println(port + " -> realRemoteDevice=" + info.realRemoteDevice()
                        + ", friendlyName=" + info.friendlyName()));
    }
}
