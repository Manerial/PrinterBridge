package org.printerbridge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.printerbridge.printer.PrintContentType;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterStatus;
import org.printerbridge.printer.PrinterType;
import org.printerbridge.service.PrinterDiscovery;
import org.printerbridge.service.PrinterRegistry;
import org.printerbridge.service.PrintJobService;

class ApiServerTest {

    private static final Printer KNOWN_PRINTER =
            new Printer("known-id", "Known Printer", PrinterType.BLUETOOTH_THERMAL, PrinterStatus.ONLINE);

    private final ObjectMapper mapper = new ObjectMapper();

    private Javalin app;

    @BeforeEach
    void startServer() {
        // Fake registry/print-job-service by default. The real ones go through OS/WMI discovery,
        // measured at ~7.5s per call on real hardware (WindowsBluetoothPortInfo, not a code bug —
        // the WMI enumeration itself is that slow here) — that used to make every test in this
        // class pay that cost even for ones that only care about an unknown id or a malformed
        // request, and made the WS tests below time out against their 5s wait. See CLAUDE.md
        // (correctif audit) and the one "hardware"-tagged test at the bottom for the case that
        // still needs the real thing.
        app = ApiServer.start(0, new PrinterRegistry(List.of(new FakeDiscovery(List.of(KNOWN_PRINTER)))),
                new PrintJobService(id -> Optional.empty(), id -> Optional.empty()));
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    @Test
    void rejectsRequestsWithAMismatchedOrigin() throws IOException, InterruptedException {
        // Loopback binding alone doesn't stop another site open in the admin's browser from calling
        // this API (see CLAUDE.md / enforceSameOrigin in ApiServer) — a request carrying a foreign
        // Origin header must be rejected outright, even though it targets 127.0.0.1 like everyone else.
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + "/printers"))
                .header("Origin", "http://evil.example")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
    }

    @Test
    void listsPrintersFromOsDiscovery() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + "/printers"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        List<Printer> actual = mapper.readValue(response.body(), new TypeReference<List<Printer>>() {
        });
        assertEquals(List.of(KNOWN_PRINTER), actual);
    }

    @Test
    void statusReturns404ForUnknownId() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + "/printers/unknown/status"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
    }

    @Test
    void statusReturns200ForAKnownPrinter() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + "/printers/" + KNOWN_PRINTER.id() + "/status"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        Printer actual = mapper.readValue(response.body(), Printer.class);
        assertEquals(KNOWN_PRINTER.id(), actual.id());
        assertEquals(KNOWN_PRINTER.type(), actual.type());
    }

    @Test
    void printReturnsErrorForUnknownPrinterId() throws Exception {
        byte[] payload = {1, 2, 3};
        String response = sendPrintRequest(app.port(), "unknown", PrintContentType.ESC_POS, payload.length, payload);

        assertTrue(response.contains("Unknown printer id"));
    }

    // Uses real discovery/print-job-service (not the fake-backed app above): the mismatch check
    // itself is already covered hardware-independently by PrintJobServiceTest.requireContentType*;
    // this one is a genuine end-to-end sanity check against a real network printer, hence tagged
    // and excluded from the default `mvn test` run like the rest of the hardware-dependent tests
    // (pom.xml, test.excludedGroups).
    @Test
    @Tag("hardware")
    void printRejectsMismatchedContentTypeForNetworkPrinter() throws Exception {
        Javalin realApp = ApiServer.start(0);
        try {
            Printer network = new PrinterRegistry().discoverAll().stream()
                    .filter(printer -> printer.type() == PrinterType.NETWORK)
                    .findFirst()
                    .orElse(null);
            Assumptions.assumeTrue(network != null, "No network printer discovered on this machine — skipping");

            byte[] payload = {1, 2, 3};
            String response =
                    sendPrintRequest(realApp.port(), network.id(), PrintContentType.ESC_POS, payload.length, payload);

            assertTrue(response.contains("only accept PDF"));
        } finally {
            realApp.stop();
        }
    }

    @Test
    void printRejectsDeclaredSizeMismatch() throws Exception {
        byte[] payload = {1, 2, 3};
        String response =
                sendPrintRequest(app.port(), "unknown", PrintContentType.PDF, payload.length + 1, payload);

        assertTrue(response.contains("does not match"));
    }

    @Test
    void acceptsPayloadsLargerThanTheDefaultWebSocketMessageLimit() throws Exception {
        // Jetty's default WS message cap is 64 KB; this is comfortably past it, standing in for a
        // real A4 label PDF. Targets an unknown id so it fails fast on content, not on transport.
        byte[] payload = new byte[200_000];
        Arrays.fill(payload, (byte) 1);

        String response = sendPrintRequest(app.port(), "unknown", PrintContentType.PDF, payload.length, payload);

        assertTrue(response.contains("Unknown printer id"));
    }

    @Test
    void printRejectsBinaryPayloadWithoutControlMessage() throws Exception {
        CompletableFuture<String> firstMessage = new CompletableFuture<>();
        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + app.port() + "/printers/unknown/print"),
                        textCollectingListener(firstMessage))
                .get(5, TimeUnit.SECONDS);

        ws.sendBinary(ByteBuffer.wrap(new byte[]{1, 2, 3}), true).get(5, TimeUnit.SECONDS);

        assertTrue(firstMessage.get(5, TimeUnit.SECONDS).contains("Expected a JSON control message"));
    }

    @Test
    void testPrintReturnsErrorForUnknownPrinterId() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + "/printers/unknown/test-print"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Consistent with statusReturns404ForUnknownId: an unknown id is a 404, not a 200 carrying
        // an error body — see UnknownPrinterException.
        assertEquals(404, response.statusCode());
    }

    // Deliberately not tested here: a successful test-print against a real, discovered printer —
    // it would actually attempt to print (cf. PrintJobServiceTest). Validate manually instead.

    @Test
    void listensOnExtraBindHostsInAdditionToLoopback() throws IOException, InterruptedException {
        // Real end-to-end check (not just parseExtraBindHosts below) that Javalin/Jetty actually
        // opens a second connector — confirmed by hand against a real Docker bridge address on
        // Linux (CLAUDE.md), but exercised here with the IPv6 loopback so it runs unattended on any
        // OS/CI runner. A second IPv4 loopback alias (e.g. 127.0.0.2) does NOT work for this: Linux
        // and Windows treat the whole 127.0.0.0/8 range as loopback out of the box, but macOS only
        // pre-configures 127.0.0.1 on lo0 — binding to another IPv4 loopback address there requires
        // an explicit `ifconfig lo0 alias` first (confirmed by a real macOS CI failure: "Can't assign
        // requested address"). ::1 is pre-configured everywhere without any extra setup.
        Javalin multiHostApp = ApiServer.start(0, new PrinterRegistry(List.of(new FakeDiscovery(List.of(KNOWN_PRINTER)))),
                new PrintJobService(id -> Optional.empty(), id -> Optional.empty()), List.of("::1"));
        try {
            int extraPort = extraConnectorPort(multiHostApp, "::1");

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://[::1]:" + extraPort + "/printers"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
        } finally {
            multiHostApp.stop();
        }
    }

    @Test
    void parseExtraBindHostsReturnsEmptyForNullOrBlank() {
        assertEquals(List.of(), ApiServer.parseExtraBindHosts(null));
        assertEquals(List.of(), ApiServer.parseExtraBindHosts(""));
        assertEquals(List.of(), ApiServer.parseExtraBindHosts("   "));
    }

    @Test
    void parseExtraBindHostsSplitsTrimsAndDropsEmptyEntries() {
        assertEquals(List.of("172.19.0.1", "10.0.0.5"),
                ApiServer.parseExtraBindHosts(" 172.19.0.1 , ,10.0.0.5,"));
    }

    private static int extraConnectorPort(Javalin app, String host) {
        return Arrays.stream(app.jettyServer().server().getConnectors())
                .filter(ServerConnector.class::isInstance)
                .map(ServerConnector.class::cast)
                .filter(connector -> host.equals(connector.getHost()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No connector bound to " + host))
                .getLocalPort();
    }

    private String sendPrintRequest(int port, String printerId, PrintContentType contentType, int declaredSize,
            byte[] payload) throws Exception {
        CompletableFuture<String> firstMessage = new CompletableFuture<>();
        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/printers/" + printerId + "/print"),
                        textCollectingListener(firstMessage))
                .get(5, TimeUnit.SECONDS);

        String controlJson = mapper.writeValueAsString(new PrintControlMessage(contentType, declaredSize));
        ws.sendText(controlJson, true).get(5, TimeUnit.SECONDS);
        ws.sendBinary(ByteBuffer.wrap(payload), true).get(5, TimeUnit.SECONDS);

        // The fake-backed tests above resolve near-instantly, so this ceiling only ever matters for
        // the "hardware"-tagged test, whose real discovery call is itself ~7.5s on real hardware
        // (measured, see CLAUDE.md) — comfortable margin above that, not a fixed wait.
        return firstMessage.get(20, TimeUnit.SECONDS);
    }

    private static WebSocket.Listener textCollectingListener(CompletableFuture<String> firstMessage) {
        return new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    firstMessage.complete(buffer.toString());
                }
                webSocket.request(1);
                return null;
            }
        };
    }

    private static final class FakeDiscovery implements PrinterDiscovery {
        private final List<Printer> printers;

        FakeDiscovery(List<Printer> printers) {
            this.printers = printers;
        }

        @Override
        public List<Printer> discover() {
            return printers;
        }

        @Override
        public Optional<Printer> findById(String id) {
            return printers.stream().filter(printer -> printer.id().equals(id)).findFirst();
        }
    }
}
