package com.example.notify.api.dto;

import com.example.notify.model.NotificationStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class SubmitNotificationResponse {

    private final String notifyId;
    private final NotificationStatus status;
}
