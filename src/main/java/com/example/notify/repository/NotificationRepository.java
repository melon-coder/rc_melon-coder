package com.example.notify.repository;

import com.example.notify.model.Notification;
import com.example.notify.model.NotificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByNotifyId(String notifyId);

    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    /**
     * 捞取「已到期且待投递」的通知，按 next_retry_at 升序（越早越优先）。
     * 只取 PENDING/RETRY：DELIVERING 由 claim 独占，避免被再次捞取导致重复投递。
     */
    @Query("select n from Notification n where n.status in :statuses and n.nextRetryAt <= :now order by n.nextRetryAt asc")
    List<Notification> findDue(@Param("statuses") List<NotificationStatus> statuses,
                               @Param("now") LocalDateTime now,
                               Pageable pageable);

    /**
     * 原子认领：仅当 status 仍为 PENDING/RETRY 时置为 DELIVERING 并写入租约。
     * 返回受影响行数（1=认领成功，0=已被并发认领）。
     * 用条件 UPDATE 而非「先查再改」，避免并发下同一任务被多个线程/实例重复认领。
     */
    @Modifying
    @Query(value = "update notification set status = 'DELIVERING', deliver_started_at = :now, updated_at = :now " +
            "where id = :id and status in ('PENDING', 'RETRY')", nativeQuery = true)
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 回收崩溃残留：把租约超时（deliver_started_at 早于 timeout）的 DELIVERING 重置为 RETRY，
     * 并立即到期（next_retry_at=now），让调度器重新捞取。
     * 保证投递进程崩溃后，任务不会永久卡在「在途」状态。
     */
    @Modifying
    @Query(value = "update notification set status = 'RETRY', next_retry_at = :now, updated_at = :now " +
            "where status = 'DELIVERING' and deliver_started_at < :timeout", nativeQuery = true)
    int reclaimStaleDelivering(@Param("timeout") LocalDateTime timeout, @Param("now") LocalDateTime now);

    long countByStatus(NotificationStatus status);
}
