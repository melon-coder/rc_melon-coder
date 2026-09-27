package com.example.notify.model;

/**
 * 通知投递状态机。
 *
 * 流转：PENDING/RETRY →(claim)→ DELIVERING →(2xx) SUCCESS | (失败) RETRY | (超限/超时) DEAD。
 *
 * 其中 PENDING / RETRY / SUCCESS / DEAD 是持久化的稳定状态；
 * DELIVERING 是「在途」状态，由 claim 原子写入并携带 deliver_started_at 租约，
 * 用于防止同一任务被并发重复投递；进程崩溃残留的 DELIVERING 由 reaper 定时回收为 RETRY。
 * 手动重投（retry）会把 DEAD 重置回 PENDING 重新开始。
 */
public enum NotificationStatus {
    PENDING,
    DELIVERING,
    RETRY,
    SUCCESS,
    DEAD
}
