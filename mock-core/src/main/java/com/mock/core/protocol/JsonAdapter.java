package com.mock.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mock.core.config.EndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON 协议适配器，匹配金联汇通、北京数字认证系列接口（application/json）。
 */
public class JsonAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(JsonAdapter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(EndpointConfig config) {
        String ct = config.getContentType();
        return ct != null && ct.startsWith("application/json");
    }

    @Override
    public Mono<Map<String, String>> extractParams(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
            .map(dataBuffer -> {
                try {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    String body = new String(bytes, StandardCharsets.UTF_8);
                    if (body.trim().isEmpty()) {
                        return Collections.<String, String>emptyMap();
                    }
                    JsonNode root;
                    try {
                        root = objectMapper.readTree(body);
                    } catch (Exception e) {
                        log.warn("Failed to parse JSON body: {}", e.getMessage());
                        return Collections.<String, String>emptyMap();
                    }
                    Map<String, String> params = new LinkedHashMap<>();
                    flattenJson("", root, params);
                    log.debug("Extracted {} JSON params", params.size());
                    return params;
                } finally {
                    DataBufferUtils.release(dataBuffer);
                }
            })
            .onErrorResume(e -> {
                log.warn("Failed to parse JSON body: {}", e.getMessage());
                return Mono.just(Collections.emptyMap());
            })
            .defaultIfEmpty(Collections.emptyMap());
    }

    /** 递归扁平化 JSON 对象，嵌套 key 使用 dot-notation（如 user.name）。 */
    private void flattenJson(String prefix, JsonNode node, Map<String, String> result) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                flattenJson(key, field.getValue(), result);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String key = prefix + "[" + i + "]";
                flattenJson(key, node.get(i), result);
            }
        } else if (node.isValueNode()) {
            result.put(prefix, node.asText());
        }
    }
}
