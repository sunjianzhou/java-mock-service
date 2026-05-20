package com.mock.core.protocol;

import com.mock.core.config.EndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Form-encoded 协议适配器，匹配证通系列接口（application/x-www-form-urlencoded）。
 */
public class FormUrlEncodedAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(FormUrlEncodedAdapter.class);

    @Override
    public boolean supports(EndpointConfig config) {
        String ct = config.getContentType();
        return ct != null && ct.startsWith("application/x-www-form-urlencoded");
    }

    @Override
    public Mono<Map<String, String>> extractParams(ServerWebExchange exchange) {
        return exchange.getFormData()
            .map(formData -> {
                Map<String, String> params = new LinkedHashMap<>();
                for (Map.Entry<String, java.util.List<String>> entry : formData.entrySet()) {
                    String key = entry.getKey();
                    java.util.List<String> values = entry.getValue();
                    if (values != null && !values.isEmpty()) {
                        params.put(key, values.size() == 1 ? values.get(0) : String.join(",", values));
                    }
                }
                log.debug("Extracted {} form params", params.size());
                return params;
            })
            .onErrorResume(e -> {
                log.warn("Failed to parse form body: {}", e.getMessage());
                return Mono.just(Collections.emptyMap());
            });
    }
}
