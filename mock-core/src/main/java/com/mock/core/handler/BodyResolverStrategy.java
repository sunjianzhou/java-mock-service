package com.mock.core.handler;

import com.mock.core.config.EndpointConfig;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 响应体解析策略接口，各实现按 {@link #order()} 升序组成责任链。
 * 新增响应来源（Groovy 脚本、随机数据生成器等）只需实现此接口并标注 @Component。
 */
public interface BodyResolverStrategy {

    /** 是否支持当前 endpoint 配置。 */
    boolean supports(EndpointConfig config);

    /** 解析响应体（异步）。 */
    Mono<String> resolve(EndpointConfig config, Map<String, String> params);

    /** 优先级，数字越小越优先。Script=1 > File=2 > Conditional=3 > Static=4 */
    int order();
}
