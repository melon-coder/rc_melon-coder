package com.example.notify.dispatcher;

import com.example.notify.model.Notification;
import com.example.notify.support.DeliveryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpSenderTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private int responseCode = 200;
    private String responseBody = "ok";

    private HttpSender sender;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(1000);
        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {
            }
        });
        sender = new HttpSender(restTemplate, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private Notification notification() {
        Notification n = new Notification();
        n.setUrl("http://localhost:" + server.getAddress().getPort() + "/callback");
        n.setMethod("POST");
        n.setHeaders("{\"Authorization\":\"Bearer token\"}");
        n.setBody("{\"event\":\"user.registered\"}");
        return n;
    }

    @Test
    void returnsSuccessOn2xx() {
        responseCode = 200;
        DeliveryResult result = sender.send(notification());
        assertTrue(result.isSuccess());
        assertEquals(1, requests.get());
    }

    @Test
    void returnsFailureOn5xx() {
        responseCode = 500;
        responseBody = "boom";
        DeliveryResult result = sender.send(notification());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().startsWith("HTTP 500"));
    }

    @Test
    void returnsFailureOnConnectionError() {
        Notification n = notification();
        n.setUrl("http://localhost:1/unreachable");
        DeliveryResult result = sender.send(n);
        assertFalse(result.isSuccess());
    }
}
