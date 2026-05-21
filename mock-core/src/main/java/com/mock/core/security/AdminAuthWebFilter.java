package com.mock.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Admin 端点鉴权过滤器：对 /mock/_admin/** 路径校验 X-API-Key。
 * 未配置 api-key 时跳过鉴权（向后兼容）。
 */
@Component
@Order(-100)
public class AdminAuthWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthWebFilter.class);

    private final String apiKey;

    public AdminAuthWebFilter(@Value("${mock.admin.api-key:}") String apiKey) {
        this.apiKey = (apiKey != null && !apiKey.trim().isEmpty()) ? apiKey.trim() : null;
        if (this.apiKey != null) {
            log.info("Admin API key authentication enabled");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith("/mock/_admin")) {
            return chain.filter(exchange);
        }
        if (apiKey == null) {
            return chain.filter(exchange);
        }
        String providedKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (apiKey.equals(providedKey)) {
            return chain.filter(exchange);
        }
        log.warn("Admin endpoint access denied (bad or missing X-API-Key): {}", path);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"status\":\"error\",\"message\":\"Unauthorized: invalid or missing X-API-Key\"}"
            .getBytes(StandardCharsets.UTF_8);
        DataBuffer buf = new DefaultDataBufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buf));
    }
}
