package com.example.notify.api.dto;

import com.example.notify.model.Notification;
import com.example.notify.model.NotificationStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class NotificationView {

    private String notifyId;
    private NotificationStatus status;
    private int attemptCount;
    private int maxAttempts;
    private LocalDateTime nextRetryAt;
    private LocalDateTime deadlineAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static NotificationView from(Notification n) {
        NotificationView view = new NotificationView();
        view.setNotifyId(n.getNotifyId());
        view.setStatus(n.getStatus());
        view.setAttemptCount(n.getAttemptCount());
        view.setMaxAttempts(n.getMaxAttempts());
        view.setNextRetryAt(n.getNextRetryAt());
        view.setDeadlineAt(n.getDeadlineAt());
        view.setLastError(n.getLastError());
        view.setCreatedAt(n.getCreatedAt());
        view.setUpdatedAt(n.getUpdatedAt());
        return view;
    }
}
