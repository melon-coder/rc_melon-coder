package com.example.notify.support;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class DeliveryResult {

    private final boolean success;
    private final String error;

    public static DeliveryResult success() {
        return new DeliveryResult(true, null);
    }

    public static DeliveryResult failure(String error) {
        return new DeliveryResult(false, error);
    }
}
