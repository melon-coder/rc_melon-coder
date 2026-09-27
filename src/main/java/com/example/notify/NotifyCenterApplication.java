package com.example.notify;

import com.example.notify.config.NotifyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(NotifyProperties.class)
public class NotifyCenterApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifyCenterApplication.class, args);
    }
}
