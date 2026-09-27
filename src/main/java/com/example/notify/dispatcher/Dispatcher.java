package com.example.notify.dispatcher;

import com.example.notify.config.NotifyProperties;
import com.example.notify.model.Notification;
import com.example.notify.model.NotificationStatus;
import com.example.notify.repository.NotificationRepository;
import com.example.notify.service.ClaimService;
import com.example.notify.service.DeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 调度器，用两个定时任务驱动整个投递循环：
 * - poll：捞取到期任务并原子认领，交给有界线程池异步投递；
 * - reap：回收崩溃残留的在途（DELIVERING）任务。
 */
@Component
public class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
    private static final List<NotificationStatus> DUE_STATUSES =
            Arrays.asList(NotificationStatus.PENDING, NotificationStatus.RETRY);

    private final NotificationRepository repository;
    private final ClaimService claimService;
    private final DeliveryService deliveryService;
    private final NotifyProperties properties;
    private final ThreadPoolTaskExecutor deliveryExecutor;
    private final Clock clock;

    public Dispatcher(NotificationRepository repository,
                      ClaimService claimService,
                      DeliveryService deliveryService,
                      NotifyProperties properties,
                      @Qualifier("deliveryExecutor") ThreadPoolTaskExecutor deliveryExecutor,
                      Clock clock) {
        this.repository = repository;
        this.claimService = claimService;
        this.deliveryService = deliveryService;
        this.properties = properties;
        this.deliveryExecutor = deliveryExecutor;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notify.dispatcher.poll-interval-ms:1000}")
    public void poll() {
        LocalDateTime now = LocalDateTime.now(clock);
        // 每轮只捞一个批次；认领成功后立即交给线程池，避免本轮被慢 HTTP 调用阻塞
        List<Notification> due = repository.findDue(
                DUE_STATUSES, now, PageRequest.of(0, properties.getDispatcher().getBatchSize()));
        for (Notification n : due) {
            if (claimService.claim(n.getId())) {
                deliveryExecutor.execute(() -> deliveryService.deliver(n.getId()));
            }
        }
    }

    @Scheduled(fixedDelayString = "${notify.dispatcher.reaper-interval-ms:60000}")
    public void reap() {
        // 租约超时的 DELIVERING 视为「投递进程已崩溃」，回收为 RETRY 重新投递
        LocalDateTime timeout = LocalDateTime.now(clock).minusSeconds(properties.getDispatcher().getLeaseSeconds());
        int reclaimed = claimService.reclaimStale(timeout);
        if (reclaimed > 0) {
            log.warn("reclaimed {} stale DELIVERING notifications", reclaimed);
        }
    }
}
