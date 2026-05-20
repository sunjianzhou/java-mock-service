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
    private static final org.yaml.snakeyaml.Yaml YAML = new org.yaml.snakeyaml.Yaml();

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
        metrics.setRecordingActive(true);
        return jsonOk("{\"status\":\"ok\",\"recording\":true}");
    }

    public Mono<ServerResponse> stopRecording() {
        recordingStore.stopRecording();
        metrics.setRecordingActive(false);
        return jsonOk("{\"status\":\"ok\",\"recording\":false,\"count\":" + recordingStore.size() + "}");
    }

    public Mono<ServerResponse> startReplay() {
        recordingStore.startReplay();
        metrics.setReplayActive(true);
        return jsonOk("{\"status\":\"ok\",\"replaying\":true,\"count\":" + recordingStore.size() + "}");
    }

    public Mono<ServerResponse> stopReplay() {
        recordingStore.stopReplay();
        metrics.setReplayActive(false);
        return jsonOk("{\"status\":\"ok\",\"replaying\":false}");
    }

    public Mono<ServerResponse> listRecordings() {
        try {
            byte[] jsonBytes = OBJECT_MAPPER.writeValueAsBytes(recordingStore.list());
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(new DefaultDataBufferFactory().wrap(jsonBytes)), DataBuffer.class);
        } catch (Exception e) {
            log.error("Failed to serialize recordings", e);
            return ServerResponse.status(500).build();
        }
    }

    public Mono<ServerResponse> clearRecordings() {
        recordingStore.clear();
        return jsonOk("{\"status\":\"ok\",\"message\":\"recordings cleared\"}");
    }

    public Mono<ServerResponse> saveRecordings() {
        return Mono.fromCallable(() -> {
            recordingStore.saveToFile();
            return recordingStore.size();
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(count -> jsonOk("{\"status\":\"ok\",\"message\":\"saved " + count + " recordings\"}"))
        .onErrorResume(e -> {
            log.error("Failed to save recordings", e);
            return ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(new DefaultDataBufferFactory().wrap(
                    "{\"status\":\"error\",\"message\":\"Failed to save recordings\"}"
                    .getBytes(StandardCharsets.UTF_8))), DataBuffer.class);
        });
    }

    public Mono<ServerResponse> loadRecordings() {
        return Mono.fromCallable(() -> recordingStore.loadFromFile())
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(count -> jsonOk("{\"status\":\"ok\",\"loaded\":" + count + "}"))
            .onErrorResume(e -> {
                log.error("Failed to load recordings", e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Mono.just(new DefaultDataBufferFactory().wrap(
                        "{\"status\":\"error\",\"message\":\"Failed to load recordings\"}"
                        .getBytes(StandardCharsets.UTF_8))), DataBuffer.class);
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
                .body(Mono.just(new DefaultDataBufferFactory().wrap(jsonBytes)), DataBuffer.class);
        } catch (Exception e) {
            log.error("Failed to serialize route list", e);
            return ServerResponse.status(500).build();
        }
    }

    // ---- 热加载 ----

    @SuppressWarnings("unchecked")
    public Mono<ServerResponse> reload(ServerRequest request, ReloadableConfigHolder holder) {
        return Mono.fromCallable(() -> {
            Map<String, Object> root = loadConfigYaml();
            MockConfigProperties newConfig = YamlConfigParser.parse(root);

            MockConfigProperties old;
            synchronized (holder) {
                old = holder.get();
                holder.set(newConfig);
            }

            log.info("Config reloaded: {} endpoints + {} websockets (was {} endpoints)",
                newConfig.getEndpoints().size(), newConfig.getWebsockets().size(),
                old != null ? old.getEndpoints().size() : 0);
            return "{\"status\":\"ok\",\"endpoints\":" + newConfig.getEndpoints().size()
                + ",\"websockets\":" + newConfig.getWebsockets().size() + "}";
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(result -> {
            if (result.startsWith("{\"status\":\"ok\"")) {
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Mono.just(
                        new DefaultDataBufferFactory().wrap(result.getBytes(StandardCharsets.UTF_8))),
                        DataBuffer.class);
            }
            return ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(
                    new DefaultDataBufferFactory().wrap(result.getBytes(StandardCharsets.UTF_8))),
                    DataBuffer.class);
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
                .body(Mono.just(new DefaultDataBufferFactory().wrap(jsonBytes)), DataBuffer.class);
        } catch (Exception e) {
            log.error("Failed to build Postman collection", e);
            return ServerResponse.status(500).build();
        }
    }

    // ----

    private Mono<ServerResponse> jsonOk(String json) {
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(Mono.just(new DefaultDataBufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8))),
                DataBuffer.class);
    }
}
