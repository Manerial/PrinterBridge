package org.printerbridge.api;

import io.javalin.Javalin;
import io.javalin.http.NotFoundResponse;
import org.printerbridge.service.PrinterRegistry;

public final class ApiServer {

    private static final String BIND_HOST = "127.0.0.1";

    private ApiServer() {
    }

    public static Javalin start(int port) {
        return Javalin.create(config -> {
            config.jetty.host = BIND_HOST;
            config.jetty.port = port;
            config.routes.get("/printers", ctx -> ctx.json(PrinterRegistry.discoverAll()));
            config.routes.get("/printers/{id}/status", ctx -> {
                String id = ctx.pathParam("id");
                ctx.json(PrinterRegistry.findStatus(id)
                        .orElseThrow(() -> new NotFoundResponse("Unknown printer id: " + id)));
            });
        }).start();
    }
}
