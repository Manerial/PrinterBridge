package org.printerbridge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.printerbridge.printer.Printer;
import org.printerbridge.service.PrinterRegistry;

class ApiServerTest {

    private final ObjectMapper mapper = new ObjectMapper();

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
        assertEquals(PrinterRegistry.discoverAll(), actual);
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
        List<Printer> printers = PrinterRegistry.discoverAll();
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
}
