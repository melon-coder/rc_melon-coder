package com.example.notify.service;

import com.example.notify.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 认领/回收的薄封装，用 @Transactional 保证 @Modifying 原生更新在事务内执行并提交。
 *
 * 单独成类是为了让 claim 在「独立短事务」中提交：Dispatcher 调用 claim 后，
 * 该事务立即提交，之后投递给线程池的任务才能读到 DELIVERING 状态；
 * 若把 claim 混在 Dispatcher 的整轮捞取长事务里，会出现「认领未提交、投递已开始」的可见性竞态。
 */
@Service
public class ClaimService {

    private final NotificationRepository repository;
    private final Clock clock;

    public ClaimService(NotificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public boolean claim(Long id) {
        return repository.claim(id, LocalDateTime.now(clock)) == 1;
    }

    @Transactional
    public int reclaimStale(LocalDateTime timeout) {
        return repository.reclaimStaleDelivering(timeout, LocalDateTime.now(clock));
    }
}
