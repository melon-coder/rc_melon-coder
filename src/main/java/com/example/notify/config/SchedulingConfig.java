package com.example.notify.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度开关：生产默认开启（matchIfMissing=true）；
 * 测试通过 notify.scheduling.enabled=false 关闭，避免测试上下文里轮询线程干扰断言。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "notify.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
