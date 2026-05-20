package com.mock.core.handler;

import com.mock.core.config.EndpointConfig;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/** 优先级 3：条件响应（conditionalResponse），按参数值返回不同响应体。 */
@Component
public class ConditionalBodyResolverStrategy implements BodyResolverStrategy {

    @Override
    public boolean supports(EndpointConfig config) {
        EndpointConfig.ConditionalResponse cond = config.getConditionalResponse();
        return cond != null && cond.getParam() != null;
    }

    @Override
    public Mono<String> resolve(EndpointConfig config, Map<String, String> params) {
        EndpointConfig.ConditionalResponse cond = config.getConditionalResponse();
        String paramValue = params.getOrDefault(cond.getParam(), "");
        String matched = cond.getCases().get(paramValue);
        if (matched != null) return Mono.just(matched);
        if (cond.getDefaultResponse() != null) return Mono.just(cond.getDefaultResponse());
        return Mono.justOrEmpty(config.getResponseBody());
    }

    @Override
    public int order() { return 3; }
}
