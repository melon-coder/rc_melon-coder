package com.example.notify.support;

import com.example.notify.model.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认死信告警实现：以 ERROR 级别日志告警，保证死信「可见」。
 * 需要外部通道时，新增一个 DeadLetterNotifier 实现即可，无需改动投递逻辑。
 */
@Component
public class LoggingDeadLetterNotifier implements DeadLetterNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingDeadLetterNotifier.class);

    @Override
    public void notifyDead(Notification notification) {
        log.error("DEAD LETTER: notifyId={} supplier={} attempts={}/{} lastError={}",
                notification.getNotifyId(), notification.getSupplier(),
                notification.getAttemptCount(), notification.getMaxAttempts(),
                notification.getLastError());
    }
}
