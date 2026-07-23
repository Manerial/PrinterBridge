package org.printerbridge;

import org.printerbridge.api.ApiServer;

public final class Main {

    private static final int PORT = 7420;

    private Main() {
    }

    public static void main(String[] args) {
        ApiServer.start(PORT);
    }
}
