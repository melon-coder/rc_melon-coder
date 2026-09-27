package com.example.notify.api;

import com.example.notify.metrics.NotificationMetrics;
import com.example.notify.model.NotificationStatus;
import com.example.notify.repository.NotificationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最小指标端点：投递成功/失败/死信计数 + 各状态的实时积压量。
 * 生产可换成 Micrometer + Prometheus 的完整监控体系，本端点仅作轻量替代。
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final NotificationMetrics metrics;
    private final NotificationRepository repository;

    public MetricsController(NotificationMetrics metrics, NotificationRepository repository) {
        this.metrics = metrics;
        this.repository = repository;
    }

    @GetMapping
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("delivered", metrics.getDelivered());
        result.put("failed", metrics.getFailed());
        result.put("dead", metrics.getDead());
        result.put("pending", repository.countByStatus(NotificationStatus.PENDING));
        result.put("retrying", repository.countByStatus(NotificationStatus.RETRY));
        result.put("delivering", repository.countByStatus(NotificationStatus.DELIVERING));
        return result;
    }
}
