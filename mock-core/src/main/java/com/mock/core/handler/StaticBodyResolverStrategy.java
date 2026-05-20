package com.mock.core.handler;

import com.mock.core.config.EndpointConfig;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/** 优先级 4（兜底）：静态响应体（responseBody）。 */
@Component
public class StaticBodyResolverStrategy implements BodyResolverStrategy {

    @Override
    public boolean supports(EndpointConfig config) {
        return true;
    }

    @Override
    public Mono<String> resolve(EndpointConfig config, Map<String, String> params) {
        return Mono.justOrEmpty(config.getResponseBody());
    }

    @Override
    public int order() { return 4; }
}
