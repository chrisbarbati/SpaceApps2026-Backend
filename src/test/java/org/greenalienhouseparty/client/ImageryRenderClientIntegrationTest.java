package org.greenalienhouseparty.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.greenalienhouseparty.exception.ImageryProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Verifies the imagery render client against a local HTTP server: images are
 * returned, and errors, redirects, non-images and oversized bodies are refused.
 */
class ImageryRenderClientIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    private HttpServer server;

    private final ImageryRenderClient client = new ImageryRenderClient(Duration.ofSeconds(5), 16);

    /**
     * Starts a local provider stub with one route per behaviour.
     *
     * @throws IOException if the server cannot start
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok.png", exchange -> respond(exchange, 200, "image/png", PNG));
        server.createContext("/svg.png", exchange -> respond(exchange, 200, "image/svg+xml", "<svg/>".getBytes()));
        server.createContext("/json.png", exchange -> respond(exchange, 200, "application/json", "{}".getBytes()));
        server.createContext("/large.png", exchange -> respond(exchange, 200, "image/png", new byte[17]));
        server.createContext("/missing.png", exchange -> respond(exchange, 404, "application/json", "{}".getBytes()));
        server.createContext("/redirect.png", exchange -> {
            exchange.getResponseHeaders().set("Location", "/ok.png");
            respond(exchange, 302, "text/plain", new byte[0]);
        });
        server.start();
    }

    /**
     * Stops the local provider stub.
     */
    @AfterEach
    void stop() {
        server.stop(0);
    }

    /**
     * Verifies a PNG is returned with its bytes and media type.
     */
    @Test
    void returnsImages() {
        RenderedImage image = client.fetch(uri("/ok.png"));

        assertArrayEquals(PNG, image.bytes());
        assertEquals(MediaType.IMAGE_PNG, image.contentType());
    }

    /**
     * Verifies unusable provider responses become provider errors.
     */
    @Test
    void refusesUnusableResponses() {
        for (String path : new String[] {"/svg.png", "/json.png", "/large.png", "/missing.png", "/redirect.png"}) {
            assertThrows(ImageryProviderException.class, () -> client.fetch(uri(path)), path);
        }
    }

    /**
     * Verifies an unreachable provider becomes a provider error.
     */
    @Test
    void reportsUnreachableProvider() {
        URI closed = uri("/ok.png");
        server.stop(0);
        assertThrows(ImageryProviderException.class, () -> client.fetch(closed));
    }

    /**
     * Builds an address on the local provider stub.
     *
     * @param path the route path
     * @return the absolute address
     */
    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    /**
     * Writes a response from the local provider stub.
     *
     * @param exchange    the active request
     * @param status      the HTTP status
     * @param contentType the response media type
     * @param body        the response body
     * @throws IOException if the response cannot be written
     */
    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (var response = exchange.getResponseBody()) {
            response.write(body);
        }
    }
}
