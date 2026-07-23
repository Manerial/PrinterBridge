package org.printerbridge.api;

import io.javalin.Javalin;
import org.printerbridge.printer.PrinterRegistry;

public final class ApiServer {

    private static final String BIND_HOST = "127.0.0.1";

    private ApiServer() {
    }

    public static Javalin start(int port) {
        return Javalin.create(config -> {
            config.jetty.host = BIND_HOST;
            config.jetty.port = port;
            config.routes.get("/printers", ctx -> ctx.json(PrinterRegistry.discoverAll()));
        }).start();
    }
}
