package com.mock.core.handler;

import com.mock.core.config.EndpointConfig;
import com.mock.core.config.MockConfigProperties;
import com.mock.core.config.ReloadableConfigHolder;
import com.mock.core.metrics.MockMetrics;
import com.mock.core.protocol.ProtocolAdapter;
import com.mock.core.record.RecordedExchange;
import com.mock.core.record.RecordingStore;
import com.mock.core.script.ScriptExecutor;
import com.mock.core.metrics.RequestLogEntry;
import com.mock.core.util.BuiltinTemplateVars;
import com.mock.core.util.JsonEscape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock 请求处理器：响应渲染 + 管理端点。
 *
 * 响应体使用 DataBuffer.wrap(bytes) 写入原始字节，而非 bodyValue(String)。
 * 支持 {{param.fieldName}} 模板占位符，自动替换为请求参数值。
 */
public class MockRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(MockRequestHandler.class);

    private static final Pattern TEMPLATE_PATTERN =
        Pattern.compile("\\{\\{param\\.([^}]+)\\}\\}");
    // Fix-4: 无状态工厂，提取为静态常量
    private static final DefaultDataBufferFactory DATA_BUFFER_FACTORY = new DefaultDataBufferFactory();

    private final List<ProtocolAdapter> adapters;
    private final RecordingStore recordingStore;
    private final MockMetrics metrics;
    private final String configFilePath;
    private final ResponseBodyResolver responseBodyResolver;
    private final AdminEndpointHandler adminHandler;

    /** Spring Bean 构造器：ResponseBodyResolver 由 Spring 注入（责任链已装配好）。 */
    public MockRequestHandler(List<ProtocolAdapter> adapters, RecordingStore recordingStore,
                              MockMetrics metrics, String configFilePath,
                              ResponseBodyResolver responseBodyResolver) {
        this.adapters = adapters;
        this.recordingStore = recordingStore;
        this.metrics = metrics;
        this.configFilePath = configFilePath;
        this.responseBodyResolver = responseBodyResolver;
        this.adminHandler = new AdminEndpointHandler(recordingStore, metrics, configFilePath);
    }

    /** 测试便捷构造器：自动创建默认责任链（Nashorn 脚本 + 文件 + 条件 + 静态）。 */
    public MockRequestHandler(List<ProtocolAdapter> adapters, RecordingStore recordingStore,
                              MockMetrics metrics, String configFilePath) {
        this(adapters, recordingStore, metrics, configFilePath,
             new ResponseBodyResolver(new ScriptExecutor()));
    }

    public Mono<ServerResponse> handle(ServerRequest request, EndpointConfig config) {
        long start = System.currentTimeMillis();
        String method = request.method().name();
        String path = request.uri().getPath();
        log.info("Mock matched: {} {} -> endpoint={}", method, path, config.getId());

        // 回放模式: 按 endpointId 查找已录制的响应（兼容 pathPattern 动态路由）
        if (recordingStore.isReplaying()) {
            RecordedExchange replay = recordingStore.findMatchByEndpoint(method, config.getId());
            if (replay != null) {
                log.info("Replaying recorded response: {}", replay.getId());
                return buildReplayResponse(replay)
                    .doFinally(s -> metrics.recordRequest(method, config.getId(),
                        replay.getResponseStatus(), System.currentTimeMillis() - start));
            }
        }

        Mono<Map<String, String>> paramsMono = adapters.stream()
            .filter(a -> a.supports(config))
            .findFirst()
            .<Mono<Map<String, String>>>map(a -> a.extractParams(request.exchange()))
            .orElse(Mono.just(Collections.emptyMap()));

        return paramsMono.flatMap(params -> {
            // 合并路径变量（pathPattern {var}），body 参数优先级更高
            Map<String, String> merged = new LinkedHashMap<>();
            // 1) 从动态路由器的属性中读取（热加载模式）
            Object pathVarsAttr = request.attributes().get("mock.pathVariables");
            if (pathVarsAttr instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> dynamicPathVars = (Map<String, String>) pathVarsAttr;
                merged.putAll(dynamicPathVars);
            }
            // 2) 从原生 RouterFunctions 的 pathVariables 读取（静态路由模式）
            try {
                merged.putAll(request.pathVariables());
            } catch (Exception ignored) {
                // 无 pathPattern 时 pathVariables() 可能抛异常
            }
            merged.putAll(params);  // body 参数覆盖路径变量

            if (!merged.isEmpty()) {
                log.debug("Extracted params for {}: {}", config.getId(), merged);
            }
            EndpointConfig.ValidationConfig validation = config.getValidation();
            if (validation != null && hasMissingRequired(merged, validation)) {
                String errBody = applyTemplateWithType(validation.getErrorBody(), merged, config.getResponseContentType());
                return buildErrorResponse(validation, merged, config, validation.getErrorBody())
                    .doOnSuccess(resp -> doRecord(method, path, config, merged,
                        validation.getErrorStatus(), config.getResponseContentType(), errBody))
                    .doFinally(s -> {
                        long dur = System.currentTimeMillis() - start;
                        metrics.recordRequest(method, config.getId(), validation.getErrorStatus(), dur);
                        metrics.addRequestLog(buildLogEntry(method, path, config.getId(), merged,
                            validation.getErrorStatus(), dur, errBody));
                    });
            }
            // Fix-7: 格式校验失败使用 formatErrorBody（未配置时回退到 errorBody），与必填缺失错误可区分
            if (validation != null && hasInvalidFormat(merged, validation)) {
                String formatErrBody = validation.resolveFormatErrorBody();
                String appliedBody = applyTemplateWithType(formatErrBody, merged, config.getResponseContentType());
                return buildErrorResponse(validation, merged, config, formatErrBody)
                    .doOnSuccess(resp -> doRecord(method, path, config, merged,
                        validation.getErrorStatus(), config.getResponseContentType(), appliedBody))
                    .doFinally(s -> {
                        long dur = System.currentTimeMillis() - start;
                        metrics.recordRequest(method, config.getId(), validation.getErrorStatus(), dur);
                        metrics.addRequestLog(buildLogEntry(method, path, config.getId(), merged,
                            validation.getErrorStatus(), dur, appliedBody));
                    });
            }
            return resolveResponseBody(config, merged)
                .flatMap(body -> buildSuccessResponse(config, body)
                    .doOnSuccess(resp -> doRecord(method, path, config, merged,
                        config.getResponseStatus(), config.getResponseContentType(), body))
                    .doFinally(s -> {
                        long dur = System.currentTimeMillis() - start;
                        metrics.recordRequest(method, config.getId(), config.getResponseStatus(), dur);
                        metrics.addRequestLog(buildLogEntry(method, path, config.getId(), merged,
                            config.getResponseStatus(), dur, body));
                    }));
        });
    }


    private RequestLogEntry buildLogEntry(String method, String path, String endpointId,
                                          Map<String, String> params, int status,
                                          long durationMs, String body) {
        RequestLogEntry e = new RequestLogEntry();
        e.setMethod(method);
        e.setPath(path);
        e.setEndpointId(endpointId);
        e.setParams(new LinkedHashMap<>(params));
        e.setStatus(status);
        e.setDurationMs(durationMs);
        if (body != null) {
            e.setResponsePreview(body.length() > 200 ? body.substring(0, 200) + "…" : body);
        }
        return e;
    }

    private void doRecord(String method, String path, EndpointConfig config,
                          Map<String, String> params, int status, String contentType,
                          String body) {
        if (!recordingStore.isRecording()) {
            return;
        }
        RecordedExchange record = new RecordedExchange();
        record.setMethod(method);
        record.setPath(path);
        record.setEndpointId(config.getId());
        record.setRequestParams(new LinkedHashMap<>(params));
        record.setResponseStatus(status);
        record.setResponseContentType(contentType);
        record.setResponseBody(body);
        record.setResponseDelay(config.getResponseDelay());
        recordingStore.add(record);
        log.debug("Recorded: {} {} -> {}", method, path, record.getId());
    }

    private boolean hasMissingRequired(Map<String, String> params, EndpointConfig.ValidationConfig validation) {
        for (String required : validation.getRequiredParams()) {
            String value = params.get(required);
            if (value == null || value.trim().isEmpty()) {
                log.warn("Validation failed: missing required param '{}'", required);
                return true;
            }
        }
        return false;
    }

    private boolean hasInvalidFormat(Map<String, String> params, EndpointConfig.ValidationConfig validation) {
        for (EndpointConfig.ParamRule rule : validation.getParamRules()) {
            String value = params.get(rule.getName());
            if (value != null && !value.trim().isEmpty()) {
                java.util.regex.Pattern compiled = rule.getCompiledPattern();
                // 回退：如果 pattern 是直接设置的（非 YAML 解析），compiled 可能为 null
                if (compiled == null) {
                    compiled = rule.compilePattern();
                }
                if (compiled != null && !compiled.matcher(value).matches()) {
                    log.warn("Validation failed: param '{}' format error — {} (pattern: {})",
                        rule.getName(), rule.getErrorMessage(), rule.getPattern());
                    return true;
                }
            }
        }
        return false;
    }

    /** 对模板字符串中的 {{param.xxx}} 占位符进行参数替换（测试直接调用，保留兼容签名）。 */
    String applyTemplate(String template, Map<String, String> params) {
        // 无 contentType 时回退到内容启发式判断（仅供测试调用）
        return applyTemplateInternal(template, params, isJsonTemplate(template));
    }

    /** Fix-6: 内部调用版本，基于 contentType 判断是否 JSON 转义，比内容启发式更准确。 */
    private String applyTemplateWithType(String template, Map<String, String> params, String contentType) {
        boolean escapeJson = contentType != null
            ? contentType.toLowerCase().contains("json")
            : isJsonTemplate(template);
        return applyTemplateInternal(template, params, escapeJson);
    }

    private String applyTemplateInternal(String template, Map<String, String> params, boolean escapeJson) {
        if (template == null || !template.contains("{{")) {
            return template;
        }
        // Fix-7: 先替换参数占位符，再替换内置变量（两者 pattern 不重叠，顺序无关）
        String result = template;
        if (result.contains("{{param.")) {
            Matcher matcher = TEMPLATE_PATTERN.matcher(result);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String paramName = matcher.group(1);
                String value = params.getOrDefault(paramName, "");
                if (escapeJson) {
                    value = JsonEscape.escape(value);
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
            matcher.appendTail(sb);
            result = sb.toString();
        }
        return BuiltinTemplateVars.apply(result);
    }

    private boolean isJsonTemplate(String template) {
        if (template == null) return false;
        String trimmed = template.trim();
        return (trimmed.startsWith("{") || trimmed.startsWith("["))
            && (trimmed.endsWith("}") || trimmed.endsWith("]"));
    }

    // Fix-7: errorBodyTemplate 参数使两类验证失败可返回不同响应体
    private Mono<ServerResponse> buildErrorResponse(EndpointConfig.ValidationConfig validation,
                                                     Map<String, String> params,
                                                     EndpointConfig config,
                                                     String errorBodyTemplate) {
        String body = applyTemplateWithType(errorBodyTemplate, params, config.getResponseContentType());
        byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        MediaType contentType;
        try {
            contentType = MediaType.valueOf(config.getResponseContentType());
        } catch (Exception e) {
            contentType = MediaType.APPLICATION_JSON;
        }
        return ServerResponse
            .status(HttpStatus.valueOf(validation.getErrorStatus()))
            .contentType(contentType)
            .body(Mono.just(DATA_BUFFER_FACTORY.wrap(bodyBytes)), DataBuffer.class);  // Fix-4
    }

    private Mono<ServerResponse> buildSuccessResponse(EndpointConfig config, String body) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        long delay = Math.max(0, config.getResponseDelay());
        if (config.getResponseDelayMax() > delay) {
            delay = delay + ThreadLocalRandom.current().nextLong(
                config.getResponseDelayMax() - delay + 1);
        }
        Mono<DataBuffer> bodyMono = Mono.just(DATA_BUFFER_FACTORY.wrap(bodyBytes));  // Fix-4
        if (delay > 0) {
            bodyMono = bodyMono.delayElement(Duration.ofMillis(delay));
        }
        return ServerResponse
            .status(HttpStatus.valueOf(config.getResponseStatus()))
            .contentType(MediaType.valueOf(config.getResponseContentType()))
            .header("X-Mock-Endpoint", config.getId())
            .body(bodyMono, DataBuffer.class);
    }

    /** 响应体解析：委托给 ResponseBodyResolver，然后应用模板（脚本除外）。 */
    private Mono<String> resolveResponseBody(EndpointConfig config, Map<String, String> params) {
        return responseBodyResolver.resolve(config, params)
            .map(raw -> {
                if (raw == null) {
                    return "";
                }
                if (config.getResponseScript() != null && !config.getResponseScript().isEmpty()) {
                    return raw;
                }
                // Fix-6: 使用 contentType 判断是否 JSON 转义，比内容启发式更准确
                return applyTemplateWithType(raw, params, config.getResponseContentType());
            });
    }

    private Mono<ServerResponse> buildReplayResponse(RecordedExchange replay) {
        String body = replay.getResponseBody() != null ? replay.getResponseBody() : "";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        Mono<DataBuffer> bodyMono = Mono.just(DATA_BUFFER_FACTORY.wrap(bodyBytes));  // Fix-4
        if (replay.getResponseDelay() > 0) {
            bodyMono = bodyMono.delayElement(Duration.ofMillis(replay.getResponseDelay()));
        }
        MediaType contentType = MediaType.APPLICATION_JSON;
        if (replay.getResponseContentType() != null && !replay.getResponseContentType().isEmpty()) {
            try {
                contentType = MediaType.valueOf(replay.getResponseContentType());
            } catch (Exception ignored) {
                // fallback to application/json
            }
        }
        return ServerResponse
            .status(HttpStatus.valueOf(replay.getResponseStatus()))
            .contentType(contentType)
            .header("X-Mock-Endpoint", replay.getEndpointId())
            .header("X-Mock-Replay", "true")
            .body(bodyMono, DataBuffer.class);
    }

    // ---- 录制/回放管理端点（委托给 AdminEndpointHandler）----

    public Mono<ServerResponse> startRecording() { return adminHandler.startRecording(); }
    public Mono<ServerResponse> stopRecording() { return adminHandler.stopRecording(); }
    public Mono<ServerResponse> startReplay() { return adminHandler.startReplay(); }
    public Mono<ServerResponse> stopReplay() { return adminHandler.stopReplay(); }
    public Mono<ServerResponse> listRecordings() { return adminHandler.listRecordings(); }
    public Mono<ServerResponse> clearRecordings() { return adminHandler.clearRecordings(); }
    public Mono<ServerResponse> saveRecordings() { return adminHandler.saveRecordings(); }
    public Mono<ServerResponse> loadRecordings() { return adminHandler.loadRecordings(); }

    // ---- 管理端点（委托给 AdminEndpointHandler）----

    public Mono<ServerResponse> listRoutes(MockConfigProperties config) { return adminHandler.listRoutes(config); }
    public Mono<ServerResponse> reload(ServerRequest request, ReloadableConfigHolder holder) { return adminHandler.reload(request, holder); }
    public Mono<ServerResponse> apply(ServerRequest request, ReloadableConfigHolder holder) { return adminHandler.apply(request, holder); }
    public Mono<ServerResponse> exportPostman(MockConfigProperties config) { return adminHandler.exportPostman(config); }
    public Mono<ServerResponse> status()     { return adminHandler.status(); }
    public Mono<ServerResponse> stats()      { return adminHandler.stats(); }
    public Mono<ServerResponse> requestLog() { return adminHandler.requestLog(); }
}
