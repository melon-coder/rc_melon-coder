package com.example.notify.service;

import com.example.notify.api.dto.SubmitNotificationRequest;
import com.example.notify.api.dto.SubmitNotificationResponse;
import com.example.notify.model.Notification;
import com.example.notify.model.NotificationStatus;
import com.example.notify.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "notify.scheduling.enabled=false")
class NotificationServiceTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository repository;

    private SubmitNotificationRequest request() {
        SubmitNotificationRequest req = new SubmitNotificationRequest();
        req.setUrl("http://example.com/callback");
        req.setMethod("POST");
        req.setBody("{\"k\":\"v\"}");
        return req;
    }

    @Test
    void submitPersistsAsPending() {
        SubmitNotificationResponse resp = notificationService.submit(request());
        Notification n = repository.findByNotifyId(resp.getNotifyId()).orElseThrow(RuntimeException::new);
        assertEquals(NotificationStatus.PENDING, n.getStatus());
        assertEquals(0, n.getAttemptCount());
    }

    @Test
    void submitIsIdempotentByIdempotencyKey() {
        SubmitNotificationRequest req = request();
        req.setIdempotencyKey("key-123");
        SubmitNotificationResponse first = notificationService.submit(req);
        SubmitNotificationResponse second = notificationService.submit(req);
        assertEquals(first.getNotifyId(), second.getNotifyId());
        assertEquals(1, repository.findByIdempotencyKey("key-123").stream().count());
    }

    @Test
    void getReturnsView() {
        SubmitNotificationResponse resp = notificationService.submit(request());
        assertEquals(resp.getNotifyId(), notificationService.get(resp.getNotifyId()).getNotifyId());
    }

    @Test
    void getMissingThrowsNotFound() {
        assertThrows(ResponseStatusException.class, () -> notificationService.get("nope"));
    }

    @Test
    void retryOnlyWorksForDead() {
        SubmitNotificationResponse resp = notificationService.submit(request());
        assertThrows(ResponseStatusException.class, () -> notificationService.retry(resp.getNotifyId()));
    }

    @Test
    void retryResetsDeadNotification() {
        SubmitNotificationRequest req = request();
        req.setIdempotencyKey("dead-key");
        SubmitNotificationResponse resp = notificationService.submit(req);
        Notification n = repository.findByNotifyId(resp.getNotifyId()).orElseThrow(RuntimeException::new);
        n.setStatus(NotificationStatus.DEAD);
        repository.save(n);

        notificationService.retry(resp.getNotifyId());
        Notification reloaded = repository.findByNotifyId(resp.getNotifyId()).orElseThrow(RuntimeException::new);
        assertEquals(NotificationStatus.PENDING, reloaded.getStatus());
        assertEquals(0, reloaded.getAttemptCount());
    }

    @Test
    void submitRejectsNonHttpScheme() {
        SubmitNotificationRequest req = request();
        req.setUrl("ftp://example.com/x");
        assertThrows(IllegalArgumentException.class, () -> notificationService.submit(req));
    }

    @Test
    void submitRejectsOversizedBody() {
        SubmitNotificationRequest req = request();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1024 * 1024 + 1; i++) {
            sb.append('a');
        }
        req.setBody(sb.toString());
        assertThrows(IllegalArgumentException.class, () -> notificationService.submit(req));
    }
}
