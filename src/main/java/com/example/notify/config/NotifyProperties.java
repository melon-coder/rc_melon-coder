package com.example.notify.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "notify")
public class NotifyProperties {

    private int defaultMaxAttempts = 15;
    private long defaultDeadlineSeconds = 7L * 24 * 3600;
    private int httpConnectTimeoutMs = 3000;
    private int httpReadTimeoutMs = 5000;
    private int maxBodySizeBytes = 1024 * 1024;
    private List<String> allowedHosts = new ArrayList<>();
    private Dispatcher dispatcher = new Dispatcher();
    private Circuit circuit = new Circuit();

    @Getter
    @Setter
    public static class Dispatcher {
        private long pollIntervalMs = 1000;
        private long reaperIntervalMs = 60000;
        private int batchSize = 100;
        private long leaseSeconds = 600;
        private int maxConcurrency = 50;
    }

    @Getter
    @Setter
    public static class Circuit {
        private int failureThreshold = 5;
        private long cooldownSeconds = 60;
        private int perSupplierMaxConcurrency = 10;
    }
}
