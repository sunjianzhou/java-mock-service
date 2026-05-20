package com.mock.core.handler;

import com.mock.core.config.EndpointConfig;
import com.mock.core.script.ScriptEngineExecutor;
import com.mock.core.script.ScriptExecutor;
import com.mock.core.util.ResourceReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 响应体解析器：按 {@link BodyResolverStrategy#order()} 升序遍历责任链，
 * 使用第一个 supports() 返回 true 的策略解析响应体。
 *
 * 默认优先级：Script(1) > File(2) > Conditional(3) > Static(4)
 * 新增响应来源只需实现 BodyResolverStrategy 并标注 @Component，无需修改此类。
 */
@Component
public class ResponseBodyResolver {

    private final List<BodyResolverStrategy> strategies;

    /** Spring DI：自动注入所有 @Component 策略，按 order() 排序。 */
    @Autowired
    public ResponseBodyResolver(List<BodyResolverStrategy> strategies) {
        this.strategies = strategies.stream()
            .sorted(Comparator.comparingInt(BodyResolverStrategy::order))
            .collect(Collectors.toList());
    }

    /** 非 Spring 场景（测试）的便捷构造器，使用默认策略链。 */
    public ResponseBodyResolver(ScriptEngineExecutor scriptExecutor) {
        this(Arrays.asList(
            new ScriptBodyResolverStrategy(scriptExecutor),
            new FileBodyResolverStrategy(new ResourceReader()),
            new ConditionalBodyResolverStrategy(),
            new StaticBodyResolverStrategy()
        ));
    }

    public Mono<String> resolve(EndpointConfig config, Map<String, String> params) {
        return strategies.stream()
            .filter(s -> s.supports(config))
            .findFirst()
            .map(s -> s.resolve(config, params))
            .orElse(Mono.just(""));
    }
}
