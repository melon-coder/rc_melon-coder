package com.example.notify.api;

import com.example.notify.api.dto.NotificationView;
import com.example.notify.api.dto.SubmitNotificationRequest;
import com.example.notify.api.dto.SubmitNotificationResponse;
import com.example.notify.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 通知对外接口：提交（异步受理）、查询状态、死信手动重投。
 * 提交接口返回 202（Accepted），表示「已持久化受理」，而非同步投递结果。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping
    public ResponseEntity<SubmitNotificationResponse> submit(@Valid @RequestBody SubmitNotificationRequest request) {
        SubmitNotificationResponse response = notificationService.submit(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{notifyId}")
    public NotificationView get(@PathVariable String notifyId) {
        return notificationService.get(notifyId);
    }

    @PostMapping("/{notifyId}/retry")
    public NotificationView retry(@PathVariable String notifyId) {
        return notificationService.retry(notifyId);
    }
}
