package com.mock.core.handler;

import com.mock.core.config.EndpointConfig;
import com.mock.core.script.ScriptEngineExecutor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/** 优先级 1：JS 脚本响应（responseScript），在 boundedElastic 线程执行避免阻塞事件循环。 */
@Component
public class ScriptBodyResolverStrategy implements BodyResolverStrategy {

    private final ScriptEngineExecutor scriptExecutor;

    public ScriptBodyResolverStrategy(ScriptEngineExecutor scriptExecutor) {
        this.scriptExecutor = scriptExecutor;
    }

    @Override
    public boolean supports(EndpointConfig config) {
        String s = config.getResponseScript();
        return s != null && !s.isEmpty();
    }

    @Override
    public Mono<String> resolve(EndpointConfig config, Map<String, String> params) {
        return Mono.fromCallable(() -> scriptExecutor.execute(config.getResponseScript(), params))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public int order() { return 1; }
}
