package com.example.notify.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * outbox 表对应的通知实体，即业务系统提交的一条待投递请求。
 *
 * 可靠性根基在于「先落库、后应答」：只要本记录成功写入，即便进程随后崩溃，
 * 调度器也能从数据库把它捞回并继续投递，因此已受理的通知不会丢失。
 * 索引 status+next_retry_at 服务于调度器的高频捞取查询。
 */
@Entity
@Table(name = "notification",
        indexes = @Index(name = "idx_notification_status_next_retry", columnList = "status,next_retry_at"))
@Getter
@Setter
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 对外返回的通知 ID（UUID），调用方据此查询/重投
    @Column(name = "notify_id", nullable = false, unique = true, length = 64)
    private String notifyId;

    // 幂等键，唯一约束；业务重复提交同一键会返回同一条记录，不重复投递
    @Column(name = "idempotency_key", unique = true, length = 128)
    private String idempotencyKey;

    // 供应商标识（默认取 URL host），是熔断/并发限流/隔离的维度
    @Column(nullable = false, length = 64)
    private String supplier;

    @Column(nullable = false, length = 2048)
    private String url;

    // HTTP 方法，统一存大写（POST/PUT/PATCH/DELETE/GET）
    @Column(nullable = false, length = 16)
    private String method;

    // 请求头，JSON 字符串（Map<String,String> 序列化而来）
    @Lob
    private String headers;

    // 请求体，原样透传给下游
    @Lob
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationStatus status;

    // 已尝试投递次数；每投一次 +1
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    // 重试上限；达到后即便还有时间也进死信
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    // 下次重试时间；调度器只捞 next_retry_at <= now 的任务
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    // 最终截止时间；超过后即便未达重试上限也进死信（长期不可用的兜底）
    @Column(name = "deadline_at", nullable = false)
    private LocalDateTime deadlineAt;

    // 在途租约：claim 时写入的认领时间，用于 reaper 识别崩溃残留
    @Column(name = "deliver_started_at")
    private LocalDateTime deliverStartedAt;

    // 最近一次失败原因，用于可观测与排查
    @Lob
    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
