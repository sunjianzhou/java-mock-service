package com.mock.core.handler;

import com.mock.core.config.EndpointConfig;
import com.mock.core.util.ResourceReader;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/** 优先级 2：外部文件响应（responseBodyFile），在 boundedElastic 线程执行阻塞 I/O。 */
@Component
public class FileBodyResolverStrategy implements BodyResolverStrategy {

    private final ResourceReader resourceReader;

    public FileBodyResolverStrategy(ResourceReader resourceReader) {
        this.resourceReader = resourceReader;
    }

    @Override
    public boolean supports(EndpointConfig config) {
        String f = config.getResponseBodyFile();
        return f != null && !f.isEmpty();
    }

    @Override
    public Mono<String> resolve(EndpointConfig config, Map<String, String> params) {
        String filePath = config.getResponseBodyFile();
        return Mono.fromCallable(() -> resourceReader.readFile(filePath))
            .subscribeOn(Schedulers.boundedElastic())
            .map(content -> content != null ? content
                : "{\"error\":\"Failed to read responseBodyFile: " + filePath + "\"}");
    }

    @Override
    public int order() { return 2; }
}
