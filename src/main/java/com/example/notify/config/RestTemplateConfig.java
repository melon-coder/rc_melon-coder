package com.example.notify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(NotifyProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getHttpConnectTimeoutMs());
        factory.setReadTimeout(properties.getHttpReadTimeoutMs());
        RestTemplate template = new RestTemplate(factory);
        // 关键：用 no-op 错误处理器，让 4xx/5xx 不抛异常、正常返回响应。
        // 投递服务把「供应商返回非 2xx」当作可重试的失败而非异常，
        // 以便干净地记录状态码与响应体，供可观测与排查使用。
        template.setErrorHandler(new NoOpResponseErrorHandler());
        return template;
    }

    private static class NoOpResponseErrorHandler implements ResponseErrorHandler {
        @Override
        public boolean hasError(ClientHttpResponse response) {
            return false;
        }

        @Override
        public void handleError(ClientHttpResponse response) {
            // 非 2xx 状态由调用方（HttpSender）作为普通失败结果处理
        }
    }
}
