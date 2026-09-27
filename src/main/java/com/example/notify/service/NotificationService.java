package com.example.notify.service;

import com.example.notify.api.dto.NotificationView;
import com.example.notify.api.dto.SubmitNotificationRequest;
import com.example.notify.api.dto.SubmitNotificationResponse;
import com.example.notify.config.NotifyProperties;
import com.example.notify.model.Notification;
import com.example.notify.model.NotificationStatus;
import com.example.notify.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 通知提交/查询/手动重投服务。
 *
 * 提交的可靠性语义是「先持久化、后应答」：只有 outbox 落库成功后，
 * 才向业务系统返回 202，从而保证「已受理」的通知不会丢失。
 */
@Service
public class NotificationService {

    private static final List<String> ALLOWED_METHODS = Arrays.asList("POST", "PUT", "PATCH", "DELETE", "GET");

    private final NotificationRepository repository;
    private final NotifyProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public NotificationService(NotificationRepository repository,
                               NotifyProperties properties,
                               ObjectMapper objectMapper,
                               Clock clock,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 提交通知（幂等）。
     *
     * 幂等策略以 idempotency_key 唯一约束为兜底，配合「独立事务插入」：
     * - 仅「先查再插」在并发提交同一键时存在竞态窗口；
     * - 仅「捕获唯一约束异常」在 JPA 中会把事务标记为 rollback-only，
     *   无法在同一事务内再查询已存在记录。
     * 因此插入放在独立事务（TransactionTemplate）中执行，冲突只回滚该事务，
     * 随后在「新事务」里查回并发提交中获胜的那条记录并返回。
     */
    public SubmitNotificationResponse submit(SubmitNotificationRequest request) {
        validate(request);

        // 快路径：键已存在则直接返回原记录，避免无谓插入
        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey != null) {
            Notification existing = transactionTemplate.execute(s ->
                    repository.findByIdempotencyKey(idempotencyKey).orElse(null));
            if (existing != null) {
                return toSubmitResponse(existing);
            }
        }

        Notification notification = build(request);

        boolean inserted;
        try {
            // 独立事务插入；若唯一约束冲突，异常只回滚本事务，不影响外层
            inserted = Boolean.TRUE.equals(transactionTemplate.execute(s -> {
                repository.saveAndFlush(notification);
                return true;
            }));
        } catch (DataIntegrityViolationException e) {
            inserted = false;
        }

        if (!inserted) {
            // 冲突后在新事务里查回并发提交的获胜记录（幂等键命中）
            Notification existing = idempotencyKey != null
                    ? transactionTemplate.execute(s -> repository.findByIdempotencyKey(idempotencyKey).orElse(null))
                    : null;
            if (existing != null) {
                return toSubmitResponse(existing);
            }
            throw new IllegalStateException("failed to persist notification");
        }

        return toSubmitResponse(notification);
    }

    @Transactional(readOnly = true)
    public NotificationView get(String notifyId) {
        return NotificationView.from(findByNotifyId(notifyId));
    }

    /**
     * 手动重投死信：把 DEAD 重置回 PENDING，清零尝试次数并重新计算截止时间，
     * 供长期不可用后人工决策恢复时使用。只允许对 DEAD 操作，避免误改正常任务。
     */
    @Transactional
    public NotificationView retry(String notifyId) {
        Notification notification = findByNotifyId(notifyId);
        if (notification.getStatus() != NotificationStatus.DEAD) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only DEAD notifications can be retried manually");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        notification.setAttemptCount(0);
        notification.setMaxAttempts(properties.getDefaultMaxAttempts());
        notification.setStatus(NotificationStatus.PENDING);
        notification.setNextRetryAt(now);
        notification.setDeadlineAt(now.plusSeconds(properties.getDefaultDeadlineSeconds()));
        notification.setLastError(null);
        notification.setUpdatedAt(now);
        return NotificationView.from(repository.save(notification));
    }

    private Notification findByNotifyId(String notifyId) {
        return repository.findByNotifyId(notifyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "notification not found: " + notifyId));
    }

    private void validate(SubmitNotificationRequest request) {
        String url = request.getUrl();
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("url is required");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid url: " + url);
        }
        // SSRF 防护：仅允许 http/https
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("url scheme must be http or https");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("url must contain a host");
        }
        String method = request.getMethod();
        if (method == null || !ALLOWED_METHODS.contains(method.toUpperCase())) {
            throw new IllegalArgumentException("unsupported method: " + method);
        }
        if (request.getMaxAttempts() != null && request.getMaxAttempts() <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (request.getDeadlineSeconds() != null && request.getDeadlineSeconds() <= 0) {
            throw new IllegalArgumentException("deadlineSeconds must be positive");
        }
        // body 大小上限：防止超大报文撑爆 outbox 表与投递内存，超出直接拒绝
        String body = request.getBody();
        if (body != null) {
            int maxBytes = properties.getMaxBodySizeBytes();
            if (body.length() > maxBytes || body.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                throw new IllegalArgumentException("body size exceeds limit of " + maxBytes + " bytes");
            }
        }
        // SSRF 防护：配置了白名单时，目标 host 必须命中
        List<String> allowedHosts = properties.getAllowedHosts();
        if (allowedHosts != null && !allowedHosts.isEmpty()) {
            String host = uri.getHost();
            boolean allowed = allowedHosts.stream().anyMatch(h -> h.equalsIgnoreCase(host));
            if (!allowed) {
                throw new IllegalArgumentException("target host not allowed: " + host);
            }
        }
    }

    private Notification build(SubmitNotificationRequest request) {
        Notification n = new Notification();
        n.setNotifyId(UUID.randomUUID().toString());
        n.setIdempotencyKey(request.getIdempotencyKey());
        // 未显式指定 supplier 时，以 URL host 作为隔离维度
        n.setSupplier(request.getSupplier() != null ? request.getSupplier() : URI.create(request.getUrl()).getHost());
        n.setUrl(request.getUrl());
        n.setMethod(request.getMethod().toUpperCase());
        try {
            n.setHeaders(request.getHeaders() == null ? "{}" : objectMapper.writeValueAsString(request.getHeaders()));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid headers", e);
        }
        n.setBody(request.getBody());
        n.setStatus(NotificationStatus.PENDING);
        n.setAttemptCount(0);
        n.setMaxAttempts(request.getMaxAttempts() != null ? request.getMaxAttempts() : properties.getDefaultMaxAttempts());
        LocalDateTime now = LocalDateTime.now(clock);
        n.setNextRetryAt(now);
        long deadlineSeconds = request.getDeadlineSeconds() != null
                ? request.getDeadlineSeconds() : properties.getDefaultDeadlineSeconds();
        n.setDeadlineAt(now.plusSeconds(deadlineSeconds));
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        return n;
    }

    private SubmitNotificationResponse toSubmitResponse(Notification n) {
        return new SubmitNotificationResponse(n.getNotifyId(), n.getStatus());
    }
}
