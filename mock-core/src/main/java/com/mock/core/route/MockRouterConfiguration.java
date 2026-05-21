package com.mock.core.route;

import com.mock.core.config.EndpointConfig;
import com.mock.core.config.MockConfigProperties;
import com.mock.core.config.ReloadableConfigHolder;
import com.mock.core.handler.MockRequestHandler;
import com.mock.core.handler.ResponseBodyResolver;
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
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Arrays;
import java.util.LinkedHashMap;
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
                                                  @Value("${mock.watch-path:}") String watchPath,
                                                  ResponseBodyResolver responseBodyResolver) {
        return new MockRequestHandler(adapters, recordingStore, metrics, watchPath, responseBodyResolver);
    }

    @Bean
    public RouterFunction<ServerResponse> mockRoutes(MockConfigProperties config,
                                                      MockRequestHandler handler,
                                                      ReloadableConfigHolder holder) {
        config.validate();
        holder.set(config);
        logEndpoints(config);

        // Fix-4: 路由表驱动，新增管理端点只需在此 Map 中加一行，无需修改路由匹配逻辑
        Map<String, HandlerFunction<ServerResponse>> adminRoutes = buildAdminRoutes(handler, holder);

        return request -> {
            String routeKey = request.method().name() + " " + request.path();
            HandlerFunction<ServerResponse> adminHandler = adminRoutes.get(routeKey);
            if (adminHandler != null) {
                return Mono.just(adminHandler);
            }

            MockConfigProperties current = holder.get();
            for (EndpointConfig ep : current.getEndpoints()) {
                if (matches(request, ep)) {
                    return Mono.just(serverRequest -> handler.handle(serverRequest, ep));
                }
            }
            return Mono.just(serverRequest -> ServerResponse.notFound().build());
        };
    }

    /** 构建管理端点路由表，新增端点在此 Map 中加一行即可。 */
    private Map<String, HandlerFunction<ServerResponse>> buildAdminRoutes(
            MockRequestHandler handler, ReloadableConfigHolder holder) {
        Map<String, HandlerFunction<ServerResponse>> routes = new LinkedHashMap<>();
        routes.put("GET /mock/_admin/routes",         req -> handler.listRoutes(holder.get()));
        routes.put("POST /mock/_admin/reload",         req -> handler.reload(req, holder));
        routes.put("POST /mock/_admin/record/start",   req -> handler.startRecording());
        routes.put("POST /mock/_admin/record/stop",    req -> handler.stopRecording());
        routes.put("POST /mock/_admin/replay/start",   req -> handler.startReplay());
        routes.put("POST /mock/_admin/replay/stop",    req -> handler.stopReplay());
        routes.put("GET /mock/_admin/recordings",      req -> handler.listRecordings());
        routes.put("DELETE /mock/_admin/recordings",   req -> handler.clearRecordings());
        routes.put("POST /mock/_admin/recordings/save",req -> handler.saveRecordings());
        routes.put("POST /mock/_admin/recordings/load",req -> handler.loadRecordings());
        routes.put("GET /mock/_admin/postman",         req -> handler.exportPostman(holder.get()));
        routes.put("POST /mock/_admin/apply",          req -> handler.apply(req, holder));
        routes.put("GET /mock/_admin/stats",           req -> handler.stats());
        routes.put("GET /mock/_admin/requests",        req -> handler.requestLog());
        return routes;
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
