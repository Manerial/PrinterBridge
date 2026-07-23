package org.printerbridge;

import io.javalin.Javalin;
import org.printerbridge.api.ApiServer;

public final class Main {

    private static final int PORT = 7420;

    private Main() {
    }

    public static void main(String[] args) {
        Javalin app = ApiServer.start(PORT);
        TrayIconSupport.install(PORT, app::stop);
    }
}
