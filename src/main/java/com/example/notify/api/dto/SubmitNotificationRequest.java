package com.example.notify.api.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import java.util.Map;

@Getter
@Setter
public class SubmitNotificationRequest {

    @NotBlank(message = "url is required")
    private String url;

    private String method = "POST";

    private String supplier;

    private Map<String, String> headers;

    private String body;

    private String idempotencyKey;

    private Integer maxAttempts;

    private Long deadlineSeconds;
}
