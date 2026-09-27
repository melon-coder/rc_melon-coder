package com.example.notify.support;

import com.example.notify.model.Notification;

/**
 * 死信告警通道的扩展点。
 * 默认实现只打印 ERROR 日志；生产可替换为钉钉/邮件/监控等外部告警通道。
 */
public interface DeadLetterNotifier {

    void notifyDead(Notification notification);
}
