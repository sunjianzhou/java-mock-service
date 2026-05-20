package com.mock.core.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 审计日志过滤器：记录每个请求的方法、路径、响应状态码和耗时。
 * 优先级最高，确保覆盖所有 Mock 请求（含 404）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditWebFilter implements WebFilter {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long start = System.currentTimeMillis();
        String method = exchange.getRequest().getMethodValue();
        String path = exchange.getRequest().getURI().getPath();

        return chain.filter(exchange)
            .doOnSuccess(v -> log(method, path, exchange, start))
            .doOnError(t -> log(method, path, exchange, start));
    }

    private void log(String method, String path, ServerWebExchange exchange, long start) {
        long duration = System.currentTimeMillis() - start;
        int status = exchange.getResponse().getRawStatusCode();
        String query = exchange.getRequest().getURI().getRawQuery();

        String pathWithQuery = query != null ? path + "?" + query : path;
        auditLog.info("{} {} → {} ({}ms)", method, pathWithQuery, status, duration);
    }
}
