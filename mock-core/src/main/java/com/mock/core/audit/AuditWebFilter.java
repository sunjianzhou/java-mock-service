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

        // Fix-3: query 参数值可能含敏感数据（身份证号、生物特征等），仅记录参数名
        String pathWithQuery = query != null ? path + "?" + maskQueryValues(query) : path;
        auditLog.info("{} {} → {} ({}ms)", method, pathWithQuery, status, duration);
    }

    private static String maskQueryValues(String query) {
        StringBuilder sb = new StringBuilder();
        for (String pair : query.split("&")) {
            if (sb.length() > 0) sb.append('&');
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                sb.append(pair, 0, eq + 1).append("***");
            } else {
                sb.append(pair);
            }
        }
        return sb.toString();
    }
}
