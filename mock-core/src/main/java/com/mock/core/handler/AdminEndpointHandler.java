package com.mock.core.handler;

import com.mock.core.config.EndpointConfig;
import com.mock.core.config.MockConfigProperties;
import com.mock.core.config.ReloadableConfigHolder;
import com.mock.core.config.YamlConfigParser;
import com.mock.core.metrics.MockMetrics;
import com.mock.core.postman.PostmanCollectionBuilder;
import com.mock.core.record.RecordingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin 端点处理器：录制/回放管理、路由清单、配置热加载、Postman 导出。
 * 从 MockRequestHandler 拆分，遵循单一职责原则。
 */
public class AdminEndpointHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminEndpointHandler.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();
    // Fix-2: SafeConstructor 禁止 !! 标签实例化任意 Java 类，防止 YAML 注入
    private static final org.yaml.snakeyaml.Yaml YAML =
        new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor());
    // Fix-4: 无状态工厂，提取为静态常量避免每次响应重复创建
    private static final DefaultDataBufferFactory DATA_BUFFER_FACTORY = new DefaultDataBufferFactory();

    private final RecordingStore recordingStore;
    private final MockMetrics metrics;
    private final String configFilePath;
    private final PostmanCollectionBuilder postmanBuilder;

    public AdminEndpointHandler(RecordingStore recordingStore, MockMetrics metrics,
                                 String configFilePath) {
        this.recordingStore = recordingStore;
        this.metrics = metrics;
        this.configFilePath = configFilePath;
        this.postmanBuilder = new PostmanCollectionBuilder();
    }

    // ---- 录制/回放 ----

    public Mono<ServerResponse> startRecording() {
        recordingStore.startRecording();
        // Fix-3: startRecording() 会自动停止回放，同步重置两个指标避免状态不一致
        metrics.setReplayActive(false);
        metrics.setRecordingActive(true);
        return jsonOk(obj().put("status", "ok").put("recording", true));
    }

    public Mono<ServerResponse> stopRecording() {
        recordingStore.stopRecording();
        metrics.setRecordingActive(false);
        return jsonOk(obj().put("status", "ok").put("recording", false).put("count", recordingStore.size()));
    }

    public Mono<ServerResponse> startReplay() {
        recordingStore.startReplay();
        // Fix-3: startReplay() 会自动停止录制，同步重置两个指标
        metrics.setRecordingActive(false);
        metrics.setReplayActive(true);
        return jsonOk(obj().put("status", "ok").put("replaying", true).put("count", recordingStore.size()));
    }

    public Mono<ServerResponse> stopReplay() {
        recordingStore.stopReplay();
        metrics.setReplayActive(false);
        return jsonOk(obj().put("status", "ok").put("replaying", false));
    }

    public Mono<ServerResponse> listRecordings() {
        try {
            byte[] jsonBytes = OBJECT_MAPPER.writeValueAsBytes(recordingStore.list());
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(DATA_BUFFER_FACTORY.wrap(jsonBytes)), DataBuffer.class);
        } catch (Exception e) {
            log.error("Failed to serialize recordings", e);
            return ServerResponse.status(500).build();
        }
    }

    public Mono<ServerResponse> clearRecordings() {
        recordingStore.clear();
        return jsonOk(obj().put("status", "ok").put("message", "recordings cleared"));
    }

    public Mono<ServerResponse> saveRecordings() {
        return Mono.fromCallable(() -> {
            recordingStore.saveToFile();
            return recordingStore.size();
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(count -> jsonOk(obj().put("status", "ok").put("message", "saved " + count + " recordings")))
        .onErrorResume(e -> {
            log.error("Failed to save recordings", e);
            return jsonError("Failed to save recordings");
        });
    }

    public Mono<ServerResponse> loadRecordings() {
        return Mono.fromCallable(() -> recordingStore.loadFromFile())
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(count -> jsonOk(obj().put("status", "ok").put("loaded", count)))
            .onErrorResume(e -> {
                log.error("Failed to load recordings", e);
                return jsonError("Failed to load recordings");
            });
    }

    // ---- 路由清单 ----

    public Mono<ServerResponse> listRoutes(MockConfigProperties config) {
        List<Map<String, Object>> routes = new ArrayList<>();
        for (EndpointConfig ep : config.getEndpoints()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ep.getId());
            m.put("method", ep.getMethod());
            m.put("path", ep.getPath());
            if (ep.getPathPattern() != null && !ep.getPathPattern().isEmpty()) {
                m.put("pathPattern", ep.getPathPattern());
            }
            m.put("description", ep.getDescription());
            // 详细字段（供管理页面展示）
            m.put("contentType", ep.getContentType());
            m.put("responseContentType", ep.getResponseContentType());
            m.put("responseStatus", ep.getResponseStatus());
            m.put("responseDelay", ep.getResponseDelay());
            m.put("responseDelayMax", ep.getResponseDelayMax());
            m.put("hasScript",      ep.getResponseScript() != null && !ep.getResponseScript().isEmpty());
            m.put("hasFile",        ep.getResponseBodyFile() != null && !ep.getResponseBodyFile().isEmpty());
            m.put("hasConditional", ep.getConditionalResponse() != null);
            // 响应体预览（截断避免数据量过大）
            String body = ep.getResponseBody();
            if (body != null && body.length() > 200) body = body.substring(0, 200) + "…";
            m.put("responseBodyPreview", body);
            // 校验配置摘要
            if (ep.getValidation() != null) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("requiredParams", ep.getValidation().getRequiredParams());
                v.put("errorStatus", ep.getValidation().getErrorStatus());
                m.put("validation", v);
            }
            routes.add(m);
        }
        for (com.mock.core.config.WebSocketEndpointConfig ws : config.getWebsockets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ws.getId());
            m.put("method", "WS");
            m.put("path", ws.getPath());
            m.put("description", ws.getDescription());
            m.put("type", "websocket");
            routes.add(m);
        }
        try {
            byte[] jsonBytes = OBJECT_MAPPER.writeValueAsBytes(routes);
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(DATA_BUFFER_FACTORY.wrap(jsonBytes)), DataBuffer.class);
        } catch (Exception e) {
            log.error("Failed to serialize route list", e);
            return ServerResponse.status(500).build();
        }
    }

    // ---- YAML 内容直接应用（管理页面编辑器使用）----

    @SuppressWarnings("unchecked")
    public Mono<ServerResponse> apply(ServerRequest request, ReloadableConfigHolder holder) {
        return request.bodyToMono(String.class)
            .flatMap(yaml -> Mono.fromCallable(() -> {
                org.yaml.snakeyaml.Yaml snakeYaml =
                    new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor());
                java.util.Map<String, Object> root =
                    (java.util.Map<String, Object>) snakeYaml.load(yaml);
                com.mock.core.config.MockConfigProperties newConfig =
                    com.mock.core.config.YamlConfigParser.parse(root);
                com.mock.core.config.MockConfigProperties old;
                synchronized (holder) {
                    old = holder.get();
                    holder.set(newConfig);
                }
                log.info("Config applied via editor: {} endpoints + {} websockets (was {} endpoints)",
                    newConfig.getEndpoints().size(), newConfig.getWebsockets().size(),
                    old != null ? old.getEndpoints().size() : 0);
                return obj()
                    .put("status", "ok")
                    .put("endpoints", newConfig.getEndpoints().size())
                    .put("websockets", newConfig.getWebsockets().size());
            }))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(this::jsonOk)
            .onErrorResume(e -> {
                log.error("Config apply failed: {}", e.getMessage(), e);
                return jsonError("Apply failed: " + e.getMessage());
            });
    }

    // ---- 热加载 ----

    @SuppressWarnings("unchecked")
    public Mono<ServerResponse> reload(ServerRequest request, ReloadableConfigHolder holder) {
        return Mono.fromCallable(() -> {
            Map<String, Object> root = loadConfigYaml();
            MockConfigProperties newConfig = YamlConfigParser.parse(root);

            MockConfigProperties old;
            // Fix-5: synchronized 仅防止两个并发 reload 交错覆盖；路由匹配通过 AtomicReference 读取，无需持有此锁
            synchronized (holder) {
                old = holder.get();
                holder.set(newConfig);
            }

            log.info("Config reloaded: {} endpoints + {} websockets (was {} endpoints)",
                newConfig.getEndpoints().size(), newConfig.getWebsockets().size(),
                old != null ? old.getEndpoints().size() : 0);
            // Fix-2: ObjectNode 构建响应，避免字符串拼接
            return obj()
                .put("status", "ok")
                .put("endpoints", newConfig.getEndpoints().size())
                .put("websockets", newConfig.getWebsockets().size());
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(this::jsonOk)
        .onErrorResume(e -> {
            log.error("Config reload failed: {}", e.getMessage(), e);
            return jsonError("Reload failed: " + e.getMessage());
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfigYaml() {
        Resource resource;
        if (configFilePath != null && !configFilePath.isEmpty()) {
            String fsPath = configFilePath.startsWith("file:") ? configFilePath.substring(5) : configFilePath;
            resource = new FileSystemResource(fsPath);
            if (!resource.exists()) resource = null;
        } else {
            resource = null;
        }
        if (resource == null || !resource.exists()) {
            resource = new ClassPathResource("mock-endpoints.yml");
            if (!resource.exists()) {
                throw new IllegalStateException("Config file not found");
            }
        }
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return (Map<String, Object>) YAML.load(reader);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config YAML", e);
        }
    }

    // ---- Postman 导出 ----

    public Mono<ServerResponse> exportPostman(MockConfigProperties config) {
        try {
            Map<String, Object> collection = postmanBuilder.build(config);
            byte[] jsonBytes = OBJECT_MAPPER.writeValueAsBytes(collection);
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=mock-service.postman_collection.json")
                .body(Mono.just(DATA_BUFFER_FACTORY.wrap(jsonBytes)), DataBuffer.class);
        } catch (Exception e) {
            log.error("Failed to build Postman collection", e);
            return ServerResponse.status(500).build();
        }
    }

    // ---- 辅助方法 ----

    /** Fix-2: 创建空 ObjectNode，替代手写 JSON 字符串拼接。 */
    private ObjectNode obj() {
        return OBJECT_MAPPER.createObjectNode();
    }

    private Mono<ServerResponse> jsonOk(ObjectNode node) {
        try {
            byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(node);
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(DATA_BUFFER_FACTORY.wrap(bytes)), DataBuffer.class);
        } catch (Exception e) {
            log.error("Failed to serialize response node", e);
            return ServerResponse.status(500).build();
        }
    }

    private Mono<ServerResponse> jsonError(String message) {
        try {
            byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(
                obj().put("status", "error").put("message", message));
            return ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(DATA_BUFFER_FACTORY.wrap(bytes)), DataBuffer.class);
        } catch (Exception e) {
            return ServerResponse.status(500).build();
        }
    }
}
