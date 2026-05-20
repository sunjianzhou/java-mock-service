package com.mock.core.protocol;

import com.mock.core.config.EndpointConfig;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 协议适配器接口。
 * 按 config.contentType 匹配（非请求 Header），为参数校验和动态响应提供扩展点。
 */
public interface ProtocolAdapter {

    /** 根据 config 中声明的 contentType 判断是否匹配此适配器。 */
    boolean supports(EndpointConfig config);

    /**
     * 从请求中提取参数。
     * 使用 ServerWebExchange 以利用 Spring 内建的 getFormData() 等缓存式解析 API。
     */
    Mono<Map<String, String>> extractParams(ServerWebExchange exchange);
}
