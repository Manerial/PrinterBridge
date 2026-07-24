package org.printerbridge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsBinaryMessageContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.printerbridge.service.PrintJobException;
import org.printerbridge.service.PrintJobService;
import org.printerbridge.service.PrinterRegistry;

public final class ApiServer {

    private static final String BIND_HOST = "127.0.0.1";
    public static final String TEST_PAGE_PATH = "/test.html";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<WsContext, PrintControlMessage> PENDING_CONTROL = new ConcurrentHashMap<>();
    private static final PrinterRegistry REGISTRY = new PrinterRegistry();

    private ApiServer() {
    }

    public static Javalin start(int port) {
        return Javalin.create(config -> {
            config.jetty.host = BIND_HOST;
            config.jetty.port = port;
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/static";
                staticFiles.location = Location.CLASSPATH;
            });
            config.routes.get("/printers", ctx -> ctx.json(REGISTRY.discoverAll()));
            config.routes.get("/printers/{id}/status", ctx -> {
                String id = ctx.pathParam("id");
                ctx.json(REGISTRY.findStatus(id)
                        .orElseThrow(() -> new NotFoundResponse("Unknown printer id: " + id)));
            });
            config.routes.post("/printers/{id}/test-print", ctx -> {
                try {
                    PrintJobService.testPrint(ctx.pathParam("id"));
                    ctx.json(PrintResult.ok());
                } catch (PrintJobException e) {
                    ctx.json(PrintResult.error(e.getMessage()));
                }
            });
            config.routes.ws("/printers/{id}/print", ws -> {
                ws.onMessage(ApiServer::onControlMessage);
                ws.onBinaryMessage(ApiServer::onPayload);
                ws.onClose(PENDING_CONTROL::remove);
                ws.onError(PENDING_CONTROL::remove);
            });
        }).start();
    }

    private static void onControlMessage(WsMessageContext ctx) {
        try {
            PrintControlMessage control = MAPPER.readValue(ctx.message(), PrintControlMessage.class);
            PENDING_CONTROL.put(ctx, control);
        } catch (JsonProcessingException e) {
            sendResult(ctx, PrintResult.error("Invalid control message: " + e.getMessage()));
            ctx.closeSession();
        }
    }

    private static void onPayload(WsBinaryMessageContext ctx) {
        PrintControlMessage control = PENDING_CONTROL.remove(ctx);
        if (control == null) {
            sendResult(ctx, PrintResult.error("Expected a JSON control message before the binary payload"));
            ctx.closeSession();
            return;
        }

        ByteBuffer buffer = ctx.data();
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        if (payload.length != control.size()) {
            sendResult(ctx, PrintResult.error(
                    "Declared size " + control.size() + " does not match received " + payload.length + " bytes"));
            ctx.closeSession();
            return;
        }

        try {
            PrintJobService.print(ctx.pathParam("id"), control.contentType(), payload);
            sendResult(ctx, PrintResult.ok());
        } catch (PrintJobException e) {
            sendResult(ctx, PrintResult.error(e.getMessage()));
        }
        ctx.closeSession();
    }

    private static void sendResult(WsContext ctx, PrintResult result) {
        try {
            ctx.send(MAPPER.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            ctx.send("{\"status\":\"ERROR\",\"message\":\"Failed to serialize result\"}");
        }
    }
}
