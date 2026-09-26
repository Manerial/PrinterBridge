package org.printerbridge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsBinaryMessageContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ServerConnector;
import org.printerbridge.exception.PrintJobException;
import org.printerbridge.service.PrintJobService;
import org.printerbridge.service.PrinterRegistry;
import org.printerbridge.exception.UnknownPrinterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ApiServer {

    private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);
    private static final String BIND_HOST = "127.0.0.1";
    // Generic escape hatch, no knowledge of Docker or any other specific caller here (see
    // CLAUDE.md): a comma-separated list of extra addresses to listen on besides BIND_HOST. Left
    // unset, behavior is identical to before. Whoever needs PrinterBridge reachable from outside
    // the host's own loopback (e.g. a container on a different network namespace) is responsible
    // for figuring out the right address and setting this — not this codebase's concern.
    private static final String EXTRA_BIND_ADDRESSES_ENV = "PRINTERBRIDGE_EXTRA_BIND_ADDRESSES";
    public static final String TEST_PAGE_PATH = "/test.html";
    // Jetty's default WS message cap (64 KB) is well under a real A4 label PDF; raised generously
    // here since the only caller is the trusted, loopback-only PluriBourse backend.
    private static final long MAX_PRINT_PAYLOAD_BYTES = 25L * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<WsContext, PrintControlMessage> PENDING_CONTROL = new ConcurrentHashMap<>();

    private ApiServer() {
    }

    public static Javalin start(int port) {
        return start(port, new PrinterRegistry(), new PrintJobService());
    }

    /**
     * Real entry point behind the public {@link #start(int)} — takes the registry/print-job
     * dependencies as parameters instead of hardcoding the real, OS-backed implementations, so
     * tests can inject fakes. Package-private: the real defaults above are the only production
     * wiring, this overload exists for {@code ApiServerTest}.
     */
    static Javalin start(int port, PrinterRegistry registry, PrintJobService printJobService) {
        return start(port, registry, printJobService, parseExtraBindHosts(System.getenv(EXTRA_BIND_ADDRESSES_ENV)));
    }

    /**
     * Takes the extra bind addresses as a parameter too (instead of always reading the environment
     * variable directly), so {@code ApiServerTest} can verify the multi-address binding itself
     * without having to fake an environment variable.
     */
    static Javalin start(int port, PrinterRegistry registry, PrintJobService printJobService,
            List<String> extraBindHosts) {
        return Javalin.create(config -> {
            bindHosts(extraBindHosts).forEach(host -> config.jetty.addConnector((server, httpConfiguration) -> {
                ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfiguration));
                connector.setHost(host);
                connector.setPort(port);
                return connector;
            }));
            config.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setMaxBinaryMessageSize(MAX_PRINT_PAYLOAD_BYTES);
                factory.setMaxFrameSize(MAX_PRINT_PAYLOAD_BYTES);
            });
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/static";
                staticFiles.location = Location.CLASSPATH;
            });
            // Loopback binding alone doesn't make this API private: 127.0.0.1 is shared by every
            // process and every browser tab on the admin's machine, not just the trusted PluriBourse
            // backend (see CLAUDE.md). That backend is a plain server-side HTTP/WS client and never
            // sends an Origin header; only a browser page does. So: no Origin header -> trusted
            // non-browser caller, allowed. Origin header present -> must match this server's own
            // origin (the only browser-facing page we serve, /test.html, satisfies that trivially);
            // any other site's origin is rejected before it can trigger a real print job or read the
            // printer inventory. Unaffected by EXTRA_BIND_ADDRESSES_ENV above: a caller reaching an
            // extra address is, by construction, a non-browser server-side client just like the
            // loopback-only PluriBourse backend was already assumed to be — it never sends an
            // Origin header either, so it's already covered by the same "no Origin -> trusted" rule.
            config.routes.before(ctx -> enforceSameOrigin(ctx, port));
            config.routes.wsBeforeUpgrade(ctx -> enforceSameOrigin(ctx, port));
            config.routes.get("/printers", ctx -> ctx.json(registry.discoverAll()));
            config.routes.get("/printers/{id}/status", ctx -> {
                String id = ctx.pathParam("id");
                ctx.json(registry.findStatus(id)
                        .orElseThrow(() -> new NotFoundResponse("Unknown printer id: " + id)));
            });
            config.routes.post("/printers/{id}/test-print", ctx -> {
                try {
                    printJobService.testPrint(ctx.pathParam("id"));
                    ctx.json(PrintResult.ok());
                } catch (UnknownPrinterException e) {
                    // Consistent with GET /printers/{id}/status: an unknown id is a 404 (the
                    // resource itself doesn't exist), not a 200 carrying an error body — unlike
                    // other PrintJobException cases below, where the id is valid but the print
                    // attempt itself failed (busy, dead link, PDF rendering, ...).
                    throw new NotFoundResponse(e.getMessage());
                } catch (PrintJobException e) {
                    LOG.warn("test-print failed: {}", e.getMessage());
                    ctx.json(PrintResult.error(e.getMessage()));
                } catch (RuntimeException e) {
                    // Mirrors the WS payload handler below: a dead/misidentified Bluetooth link
                    // (jSerialComm) or an OS-level print-service failure can throw outside
                    // PrintJobException's contract. Without this, the client would only ever see
                    // Javalin's generic 500 body instead of the same PrintResult JSON contract.
                    LOG.error("Unexpected failure while handling a test-print request", e);
                    ctx.json(PrintResult.error("Unexpected failure: " + e.getMessage()));
                }
            });
            config.routes.ws("/printers/{id}/print", ws -> {
                ws.onMessage(ApiServer::onControlMessage);
                ws.onBinaryMessage(ctx -> onPayload(ctx, printJobService));
                ws.onClose(PENDING_CONTROL::remove);
                ws.onError(PENDING_CONTROL::remove);
            });
        }).start();
    }

    /**
     * Always includes {@link #BIND_HOST}, plus whatever extra addresses were supplied — deduplicated
     * so a caller accidentally repeating {@code 127.0.0.1} in the extra list doesn't register two
     * connectors on the exact same host:port (Jetty would fail to start with "address already in
     * use").
     */
    private static List<String> bindHosts(List<String> extraBindHosts) {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        hosts.add(BIND_HOST);
        hosts.addAll(extraBindHosts);
        return List.copyOf(hosts);
    }

    static List<String> parseExtraBindHosts(String rawEnvValue) {
        if (rawEnvValue == null || rawEnvValue.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawEnvValue.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .toList();
    }

    private static void enforceSameOrigin(Context ctx, int port) {
        String origin = ctx.header("Origin");
        if (origin == null) {
            return;
        }
        String expected = "http://" + BIND_HOST + ":" + port;
        if (!origin.equals(expected)) {
            throw new ForbiddenResponse("Cross-origin requests are not allowed");
        }
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

    private static void onPayload(WsBinaryMessageContext ctx, PrintJobService printJobService) {
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
            printJobService.print(ctx.pathParam("id"), control.contentType(), payload);
            sendResult(ctx, PrintResult.ok());
        } catch (PrintJobException e) {
            LOG.warn("Print job failed: {}", e.getMessage());
            sendResult(ctx, PrintResult.error(e.getMessage()));
        } catch (RuntimeException e) {
            // A dead/misidentified Bluetooth link (jSerialComm, e.g. SerialPortInvalidPortException)
            // isn't wrapped in PrintJobException everywhere it can be thrown. Without this, the
            // exception would escape onMessage entirely, and the caller would get neither a
            // PrintResult nor a closed session — just silence until its own client-side timeout.
            LOG.error("Unexpected failure while handling a print payload", e);
            sendResult(ctx, PrintResult.error("Unexpected failure: " + e.getMessage()));
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
