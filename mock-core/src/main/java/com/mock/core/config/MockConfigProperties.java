package com.mock.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.MediaType;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mock 顶层配置，绑定 YAML 中 mock.* 前缀的属性。
 * 启动时执行完整性校验：必填字段、重复路由检测、空列表检测。
 */
@ConfigurationProperties(prefix = "mock")
public class MockConfigProperties {

    private static final Logger log = LoggerFactory.getLogger(MockConfigProperties.class);

    private List<EndpointConfig> endpoints = new ArrayList<>();
    private List<WebSocketEndpointConfig> websockets = new ArrayList<>();

    @PostConstruct
    public void validate() {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalStateException(
                "未加载到任何 mock.endpoints 配置。" +
                "请检查 mock-endpoints.yml 是否在 classpath 上，" +
                "以及 mock-boot 的 application.yml 是否包含 spring.config.import: classpath:mock-endpoints.yml");
        }

        Set<String> routeKeys = new HashSet<>();
        for (int i = 0; i < endpoints.size(); i++) {
            EndpointConfig ep = endpoints.get(i);
            String idx = "mock.endpoints[" + i + "]";

            // 必填字段校验
            if (isBlank(ep.getId())) {
                throw new IllegalStateException(idx + ".id 不能为空");
            }
            if (isBlank(ep.getMethod())) {
                throw new IllegalStateException(idx + ".method 不能为空 (endpoint: " + ep.getId() + ")");
            }
            if (isBlank(ep.getPath()) && isBlank(ep.getPathPattern())) {
                throw new IllegalStateException(
                    idx + " 至少需要设置 path 或 pathPattern 之一 (endpoint: " + ep.getId() + ")");
            }
            if (isBlank(ep.getResponseContentType())) {
                throw new IllegalStateException(
                    idx + ".responseContentType 不能为空 (endpoint: " + ep.getId() + ")");
            }
            try {
                MediaType.valueOf(ep.getResponseContentType());
            } catch (Exception e) {
                throw new IllegalStateException(
                    idx + ".responseContentType 格式无效: " + ep.getResponseContentType()
                    + " (endpoint: " + ep.getId() + ")");
            }
            // responseBody 允许为 null，前提是 responseBodyFile 或 responseScript 已设置
            if (ep.getResponseBody() == null
                && isBlank(ep.getResponseBodyFile())
                && isBlank(ep.getResponseScript())) {
                throw new IllegalStateException(
                    idx + ".responseBody 不能为 null（且未设置 responseBodyFile 或 responseScript）"
                    + " (endpoint: " + ep.getId() + ")");
            }

            // path 和 pathPattern 同时设置时的歧义提示
            if (!isBlank(ep.getPath()) && !isBlank(ep.getPathPattern())) {
                log.warn("{} 同时设置了 path 和 pathPattern，将使用 pathPattern={} (path={} 被忽略)",
                    ep.getId(), ep.getPathPattern(), ep.getPath());
            }

            // 重复路由检测
            String routeKey = ep.getMethod().toUpperCase() + " " + ep.getEffectivePath();
            if (!routeKeys.add(routeKey)) {
                throw new IllegalStateException(
                    "检测到重复路由: " + routeKey + " (endpoint: " + ep.getId() + ")");
            }
        }

        log.info("配置校验通过: {} 个 endpoint 配置合法, {} 个 websocket 端点",
            endpoints.size(), websockets != null ? websockets.size() : 0);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ---------- getters / setters ----------

    public List<EndpointConfig> getEndpoints() { return endpoints; }
    public void setEndpoints(List<EndpointConfig> endpoints) { this.endpoints = endpoints; }

    public List<WebSocketEndpointConfig> getWebsockets() { return websockets; }
    public void setWebsockets(List<WebSocketEndpointConfig> websockets) { this.websockets = websockets; }
}
