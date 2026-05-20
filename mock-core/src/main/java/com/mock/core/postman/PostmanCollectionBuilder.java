package com.mock.core.postman;

import com.mock.core.config.EndpointConfig;
import com.mock.core.config.MockConfigProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 Mock 端点配置导出为 Postman Collection v2.1 格式。
 */
public class PostmanCollectionBuilder {

    public Map<String, Object> build(MockConfigProperties config) {
        Map<String, Object> collection = new LinkedHashMap<>();

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "Mock Service API");
        info.put("description", "Mock 服务自动生成的 Postman Collection");
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        collection.put("info", info);

        List<Map<String, Object>> items = new ArrayList<>();
        for (EndpointConfig ep : config.getEndpoints()) {
            items.add(buildItem(ep));
        }
        collection.put("item", items);

        List<Map<String, String>> variables = new ArrayList<>();
        Map<String, String> baseUrl = new LinkedHashMap<>();
        baseUrl.put("key", "baseUrl");
        baseUrl.put("value", "http://localhost:8080");
        variables.add(baseUrl);
        collection.put("variable", variables);

        return collection;
    }

    private Map<String, Object> buildItem(EndpointConfig ep) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", ep.getId() + " — " + (ep.getDescription() != null ? ep.getDescription() : ""));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", ep.getMethod().toUpperCase());

        // URL
        Map<String, Object> url = new LinkedHashMap<>();
        url.put("raw", "{{baseUrl}}" + ep.getEffectivePath());
        url.put("host", Arrays.asList("{{baseUrl}}"));
        String pathStr = ep.getEffectivePath();
        if (pathStr.startsWith("/")) {
            pathStr = pathStr.substring(1);
        }
        url.put("path", Arrays.asList(pathStr.split("/")));
        request.put("url", url);

        // Headers
        List<Map<String, String>> headers = new ArrayList<>();
        if (ep.getContentType() != null && !"GET".equalsIgnoreCase(ep.getMethod())) {
            Map<String, String> ct = new LinkedHashMap<>();
            ct.put("key", "Content-Type");
            ct.put("value", ep.getContentType());
            headers.add(ct);
        }
        request.put("header", headers);

        // Body
        if (!"GET".equalsIgnoreCase(ep.getMethod()) && ep.getContentType() != null) {
            Map<String, Object> body = new LinkedHashMap<>();
            if (ep.getContentType().contains("x-www-form-urlencoded")) {
                body.put("mode", "urlencoded");
                List<Map<String, String>> urlencoded = new ArrayList<>();
                if (ep.getValidation() != null && ep.getValidation().getRequiredParams() != null) {
                    for (String param : ep.getValidation().getRequiredParams()) {
                        Map<String, String> p = new LinkedHashMap<>();
                        p.put("key", param);
                        p.put("value", "");
                        p.put("type", "text");
                        urlencoded.add(p);
                    }
                }
                body.put("urlencoded", urlencoded);
            } else if (ep.getContentType().contains("json")) {
                body.put("mode", "raw");
                // Fix-1: 请求体用必填参数骨架，而非响应体（原来的 responseBody 是响应示例，不是请求体）
                body.put("raw", buildJsonRequestSkeleton(ep));
                Map<String, String> options = new LinkedHashMap<>();
                options.put("raw", "{ \"language\": \"json\" }");
                body.put("options", options);
            } else {
                body.put("mode", "raw");
                body.put("raw", ep.getResponseBody() != null ? ep.getResponseBody() : "");
            }
            request.put("body", body);
        }

        item.put("request", request);
        return item;
    }

    /** 根据 requiredParams 生成 JSON 请求体骨架（空值占位），用于 Postman 快速填参。 */
    private String buildJsonRequestSkeleton(EndpointConfig ep) {
        if (ep.getValidation() == null
                || ep.getValidation().getRequiredParams() == null
                || ep.getValidation().getRequiredParams().isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{\n");
        List<String> params = ep.getValidation().getRequiredParams();
        for (int i = 0; i < params.size(); i++) {
            sb.append("  \"").append(params.get(i)).append("\": \"\"");
            if (i < params.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("}");
        return sb.toString();
    }
}
