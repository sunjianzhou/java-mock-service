package com.mock.core.route;

import com.mock.core.config.EndpointConfig;
import com.mock.core.config.MockConfigProperties;
import com.mock.core.config.ReloadableConfigHolder;
import com.mock.core.handler.MockRequestHandler;
import com.mock.core.metrics.MockMetrics;
import com.mock.core.protocol.FormUrlEncodedAdapter;
import com.mock.core.protocol.JsonAdapter;
import com.mock.core.protocol.ProtocolAdapter;
import com.mock.core.protocol.XmlAdapter;
import com.mock.core.record.RecordingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 动态 RouterFunction 注册，支持运行时热加载 endpoint 配置。
 */
@Configuration
@EnableConfigurationProperties(MockConfigProperties.class)
public class MockRouterConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MockRouterConfiguration.class);
    private static final PathPatternParser patternParser = new PathPatternParser();
    private final java.util.concurrent.ConcurrentHashMap<String, PathPattern> patternCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    @Bean
    public List<ProtocolAdapter> protocolAdapters() {
        return Arrays.asList(
            new FormUrlEncodedAdapter(),
            new JsonAdapter(),
            new XmlAdapter()
        );
    }

    @Bean
    public MockRequestHandler mockRequestHandler(List<ProtocolAdapter> adapters,
                                                  RecordingStore recordingStore,
                                                  MockMetrics metrics,
                                                  @Value("${mock.watch-path:}") String watchPath) {
        return new MockRequestHandler(adapters, recordingStore, metrics, watchPath);
    }

    @Bean
    public RouterFunction<ServerResponse> mockRoutes(MockConfigProperties config,
                                                      MockRequestHandler handler,
                                                      ReloadableConfigHolder holder) {
        // 初始化 holder
        config.validate();
        holder.set(config);

        logEndpoints(config);

        return request -> {
            MockConfigProperties current = holder.get();

            // 管理端点 — 路由
            if (request.method() == HttpMethod.GET
                && "/mock/_admin/routes".equals(request.path())) {
                return Mono.just(serverRequest -> handler.listRoutes(current));
            }
            if (request.method() == HttpMethod.POST
                && "/mock/_admin/reload".equals(request.path())) {
                return Mono.just(serverRequest -> handler.reload(serverRequest, holder));
            }

            // 管理端点 — 录制/回放
            if (request.method() == HttpMethod.POST
                && "/mock/_admin/record/start".equals(request.path())) {
                return Mono.just(serverRequest -> handler.startRecording());
            }
            if (request.method() == HttpMethod.POST
                && "/mock/_admin/record/stop".equals(request.path())) {
                return Mono.just(serverRequest -> handler.stopRecording());
            }
            if (request.method() == HttpMethod.POST
                && "/mock/_admin/replay/start".equals(request.path())) {
                return Mono.just(serverRequest -> handler.startReplay());
            }
            if (request.method() == HttpMethod.POST
                && "/mock/_admin/replay/stop".equals(request.path())) {
                return Mono.just(serverRequest -> handler.stopReplay());
            }
            if (request.method() == HttpMethod.GET
                && "/mock/_admin/recordings".equals(request.path())) {
                return Mono.just(serverRequest -> handler.listRecordings());
            }
            if (request.method() == HttpMethod.DELETE
                && "/mock/_admin/recordings".equals(request.path())) {
                return Mono.just(serverRequest -> handler.clearRecordings());
            }
            if (request.method() == HttpMethod.POST
                && "/mock/_admin/recordings/save".equals(request.path())) {
                return Mono.just(serverRequest -> handler.saveRecordings());
            }
            if (request.method() == HttpMethod.POST
                && "/mock/_admin/recordings/load".equals(request.path())) {
                return Mono.just(serverRequest -> handler.loadRecordings());
            }
            if (request.method() == HttpMethod.GET
                && "/mock/_admin/postman".equals(request.path())) {
                return Mono.just(serverRequest -> handler.exportPostman(current));
            }

            // 动态匹配 endpoint
            for (EndpointConfig ep : current.getEndpoints()) {
                if (matches(request, ep)) {
                    return Mono.just(serverRequest -> handler.handle(serverRequest, ep));
                }
            }

            return Mono.just(
                serverRequest -> ServerResponse.notFound().build());
        };
    }

    private boolean matches(ServerRequest request, EndpointConfig ep) {
        if (!request.method().name().equalsIgnoreCase(ep.getMethod())) {
            return false;
        }
        PathPattern pattern = patternCache.computeIfAbsent(
            ep.getEffectivePath(), patternParser::parse);
        PathContainer container = PathContainer.parsePath(request.path());
        PathPattern.PathMatchInfo info = pattern.matchAndExtract(container);
        if (info != null) {
            @SuppressWarnings("unchecked")
            Map<String, String> uriVars = (Map<String, String>) (Map<?, ?>) info.getUriVariables();
            request.attributes().put("mock.pathVariables", uriVars);
            return true;
        }
        return false;
    }

    private void logEndpoints(MockConfigProperties config) {
        log.info("Registered {} mock endpoints:", config.getEndpoints().size());
        for (EndpointConfig ep : config.getEndpoints()) {
            String pathInfo = ep.getEffectivePath();
            if (ep.getPathPattern() != null && !ep.getPathPattern().isEmpty()) {
                pathInfo = ep.getPathPattern() + " (pathPattern, path=" + ep.getPath() + " ignored)";
            }
            log.info("  {} {} -> {} (id={}, status={})",
                ep.getMethod(), pathInfo, ep.getDescription(), ep.getId(), ep.getResponseStatus());
        }
    }
}
