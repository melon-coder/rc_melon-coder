package com.example.notify;

import com.example.notify.api.dto.SubmitNotificationRequest;
import com.example.notify.api.dto.SubmitNotificationResponse;
import com.example.notify.model.NotificationStatus;
import com.example.notify.repository.NotificationRepository;
import com.example.notify.service.NotificationService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notify-int;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "notify.scheduling.enabled=true",
        "notify.dispatcher.poll-interval-ms=100",
        "notify.dispatcher.reaper-interval-ms=500",
        "notify.dispatcher.lease-seconds=5",
        "notify.http-connect-timeout-ms=500",
        "notify.http-read-timeout-ms=500"
})
class NotificationFlowIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository repository;

    private HttpServer target;
    private final AtomicInteger received = new AtomicInteger();
    private int targetCode = 200;

    private void startTarget() throws Exception {
        target = HttpServer.create(new InetSocketAddress(0), 0);
        target.createContext("/", exchange -> {
            received.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(targetCode, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        target.start();
    }

    @AfterEach
    void stopTarget() {
        if (target != null) {
            target.stop(0);
        }
    }

    private SubmitNotificationRequest request(int port) {
        SubmitNotificationRequest req = new SubmitNotificationRequest();
        req.setUrl("http://localhost:" + port + "/callback");
        req.setMethod("POST");
        req.setBody("{\"event\":\"user.registered\"}");
        return req;
    }

    @Test
    void deliversNotificationToTarget() throws Exception {
        startTarget();
        SubmitNotificationResponse resp = notificationService.submit(request(target.getAddress().getPort()));

        waitUntil(() -> repository.findByNotifyId(resp.getNotifyId())
                .map(n -> n.getStatus() == NotificationStatus.SUCCESS).orElse(false), 5000);

        assertEquals(1, received.get());
    }

    @Test
    void exhaustsAttemptsIntoDead() throws Exception {
        startTarget();
        targetCode = 500;
        SubmitNotificationRequest req = request(target.getAddress().getPort());
        req.setMaxAttempts(1);
        SubmitNotificationResponse resp = notificationService.submit(req);

        waitUntil(() -> repository.findByNotifyId(resp.getNotifyId())
                .map(n -> n.getStatus() == NotificationStatus.DEAD).orElse(false), 5000);

        assertEquals(NotificationStatus.DEAD,
                repository.findByNotifyId(resp.getNotifyId()).orElseThrow(RuntimeException::new).getStatus());
    }

    private static void waitUntil(Callable<Boolean> condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("condition not met within " + timeoutMs + "ms");
    }
}
