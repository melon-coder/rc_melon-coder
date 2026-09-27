package com.example.notify.dispatcher;

import com.example.notify.model.Notification;
import com.example.notify.support.DeliveryResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * HTTP 发送器：把一条通知原样透传为一次 HTTP 请求。
 *
 * 配合 RestTemplate 的 no-op 错误处理器，非 2xx 不抛异常，
 * 而是作为「有响应但失败」的结果返回，便于记录状态码与响应体；
 * 网络层异常（连接超时、拒绝等）统一捕获为失败结果，一律可重试。
 */
@Component
public class HttpSender {

    private static final int MAX_ERROR_LENGTH = 500;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HttpSender(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public DeliveryResult send(Notification n) {
        HttpHeaders headers = new HttpHeaders();
        String headersJson = n.getHeaders();
        if (headersJson != null && !headersJson.isEmpty()) {
            try {
                Map<String, String> map = objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {
                });
                map.forEach(headers::set);
            } catch (Exception e) {
                return DeliveryResult.failure("invalid stored headers: " + e.getMessage());
            }
        }

        HttpMethod method = HttpMethod.resolve(n.getMethod());
        if (method == null) {
            return DeliveryResult.failure("unsupported method: " + n.getMethod());
        }

        HttpEntity<String> entity = new HttpEntity<>(n.getBody(), headers);
        try {
            ResponseEntity<String> response = restTemplate.exchange(n.getUrl(), method, entity, String.class);
            int code = response.getStatusCodeValue();
            // 2xx 视为「已送达」，其余均为可重试失败
            if (code >= 200 && code < 300) {
                return DeliveryResult.success();
            }
            return DeliveryResult.failure("HTTP " + code + ": " + truncate(response.getBody()));
        } catch (Exception e) {
            return DeliveryResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // 错误信息截断，避免把超大响应体写进 last_error
    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH) + "...";
    }
}
