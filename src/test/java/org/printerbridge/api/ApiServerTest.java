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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.printerbridge.printer.PrintContentType;
import org.printerbridge.printer.Printer;
import org.printerbridge.printer.PrinterType;
import org.printerbridge.service.PrinterRegistry;

class ApiServerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PrinterRegistry registry = new PrinterRegistry();

    private Javalin app;

    @BeforeEach
    void startServer() {
        app = ApiServer.start(0);
    }

    @AfterEach
    void stopServer() {
        app.stop();
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
        assertEquals(registry.discoverAll(), actual);
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
        List<Printer> printers = registry.discoverAll();
        if (printers.isEmpty()) {
            return;
        }
        Printer expected = printers.get(0);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + "/printers/" + expected.id() + "/status"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        Printer actual = mapper.readValue(response.body(), Printer.class);
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.type(), actual.type());
    }

    @Test
    void printReturnsErrorForUnknownPrinterId() throws Exception {
        byte[] payload = {1, 2, 3};
        String response = sendPrintRequest("unknown", PrintContentType.ESC_POS, payload.length, payload);

        assertTrue(response.contains("Unknown printer id"));
    }

    @Test
    void printRejectsMismatchedContentTypeForNetworkPrinter() throws Exception {
        Printer network = registry.discoverAll().stream()
                .filter(printer -> printer.type() == PrinterType.NETWORK)
                .findFirst()
                .orElse(null);
        if (network == null) {
            return;
        }

        byte[] payload = {1, 2, 3};
        String response = sendPrintRequest(network.id(), PrintContentType.ESC_POS, payload.length, payload);

        assertTrue(response.contains("only accept PDF"));
    }

    @Test
    void printRejectsDeclaredSizeMismatch() throws Exception {
        byte[] payload = {1, 2, 3};
        String response = sendPrintRequest("unknown", PrintContentType.PDF, payload.length + 1, payload);

        assertTrue(response.contains("does not match"));
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

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Unknown printer id"));
    }

    // Deliberately not tested here: a successful test-print against a real, discovered printer —
    // it would actually attempt to print (cf. PrintJobServiceTest). Validate manually instead.

    private String sendPrintRequest(String printerId, PrintContentType contentType, int declaredSize, byte[] payload)
            throws Exception {
        CompletableFuture<String> firstMessage = new CompletableFuture<>();
        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + app.port() + "/printers/" + printerId + "/print"),
                        textCollectingListener(firstMessage))
                .get(5, TimeUnit.SECONDS);

        String controlJson = mapper.writeValueAsString(new PrintControlMessage(contentType, declaredSize));
        ws.sendText(controlJson, true).get(5, TimeUnit.SECONDS);
        ws.sendBinary(ByteBuffer.wrap(payload), true).get(5, TimeUnit.SECONDS);

        return firstMessage.get(5, TimeUnit.SECONDS);
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
}
