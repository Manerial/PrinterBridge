package org.printerbridge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.javalin.Javalin;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiServerTest {

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
    void listsNoPrintersByDefault() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + "/printers"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body());
    }
}
