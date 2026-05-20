package com.mock.core.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0/P1: MockConfigProperties @PostConstruct 校验逻辑。
 * 不依赖 Spring 容器，直接构造对象测试。
 */
class MockConfigPropertiesTest {

    private List<EndpointConfig> endpoints;

    @BeforeEach
    void setUp() {
        endpoints = new ArrayList<>();
    }

    private MockConfigProperties props() {
        MockConfigProperties p = new MockConfigProperties();
        p.setEndpoints(endpoints);
        return p;
    }

    // ---- P0: 有效配置校验通过 ----

    @Test
    void validEndpoints_shouldPassValidation() {
        endpoints.add(endpoint("test-1", "POST", "/test", null,
            "application/json", "application/json", 200, "{}"));

        assertDoesNotThrow(() -> props().validate());
    }

    // ---- P0: 必填字段缺失 ----

    @Test
    void missingId_shouldThrowIllegalStateException() {
        endpoints.add(endpoint("", "POST", "/test", null,
            "application/json", "application/json", 200, "{}"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> props().validate());
        assertTrue(ex.getMessage().contains(".id 不能为空"));
    }

    @Test
    void missingMethod_shouldThrowIllegalStateException() {
        endpoints.add(endpoint("test-1", "", "/test", null,
            "application/json", "application/json", 200, "{}"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> props().validate());
        assertTrue(ex.getMessage().contains(".method 不能为空"));
    }

    @Test
    void missingPathAndPathPattern_shouldThrowIllegalStateException() {
        endpoints.add(endpoint("test-1", "POST", "", "",
            "application/json", "application/json", 200, "{}"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> props().validate());
        assertTrue(ex.getMessage().contains("至少需要设置 path 或 pathPattern"));
    }

    @Test
    void pathPatternOnly_shouldPassValidation() {
        // pathPattern 单独设置应该合法
        endpoints.add(endpoint("test-1", "POST", null, "/api/{id}",
            "application/json", "application/json", 200, "{}"));

        assertDoesNotThrow(() -> props().validate());
    }

    @Test
    void missingResponseContentType_shouldThrowIllegalStateException() {
        endpoints.add(endpoint("test-1", "POST", "/test", null,
            "application/json", "", 200, "{}"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> props().validate());
        assertTrue(ex.getMessage().contains("responseContentType 不能为空"));
    }

    @Test
    void nullResponseBody_shouldThrowIllegalStateException() {
        endpoints.add(endpoint("test-1", "POST", "/test", null,
            "application/json", "application/json", 200, null));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> props().validate());
        assertTrue(ex.getMessage().contains("responseBody 不能为 null"));
    }

    // ---- P0: 重复路由检测 ----

    @Test
    void duplicateRoute_shouldThrowIllegalStateException() {
        endpoints.add(endpoint("ep-a", "POST", "/same-path", null,
            "application/json", "application/json", 200, "{}"));
        endpoints.add(endpoint("ep-b", "POST", "/same-path", null,
            "application/json", "application/json", 200, "{}"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> props().validate());
        assertTrue(ex.getMessage().contains("检测到重复路由"));
        assertTrue(ex.getMessage().contains("POST /same-path"));
    }

    @Test
    void samePathDifferentMethod_shouldPassValidation() {
        endpoints.add(endpoint("ep-a", "POST", "/same-path", null,
            "application/json", "application/json", 200, "{}"));
        endpoints.add(endpoint("ep-b", "GET", "/same-path", null,
            "application/json", "application/json", 200, "{}"));

        assertDoesNotThrow(() -> props().validate());
    }

    @Test
    void pathPatternDuplicate_shouldThrowIllegalStateException() {
        endpoints.add(endpoint("ep-a", "POST", null, "/api/{id}",
            "application/json", "application/json", 200, "{}"));
        endpoints.add(endpoint("ep-b", "POST", null, "/api/{id}",
            "application/json", "application/json", 200, "{}"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> props().validate());
        assertTrue(ex.getMessage().contains("检测到重复路由"));
    }

    // ---- P0: 空端点列表 ----

    @Test
    void emptyEndpoints_shouldThrowIllegalStateException() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> props().validate());
        assertTrue(ex.getMessage().contains("未加载到任何 mock.endpoints 配置"));
    }

    @Test
    void nullEndpointsList_shouldThrowIllegalStateException() {
        MockConfigProperties p = new MockConfigProperties();
        p.setEndpoints(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> p.validate());
        assertTrue(ex.getMessage().contains("未加载到任何 mock.endpoints 配置"));
    }

    // ---- P3: path + pathPattern 同时设置时 WARN 日志 ----

    @Test
    void bothPathAndPathPattern_shouldLogWarning() {
        Logger logger = (Logger) LoggerFactory.getLogger(MockConfigProperties.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            endpoints.add(endpoint("test-1", "POST", "/exact", "/pattern/{id}",
                "application/json", "application/json", 200, "{}"));
            props().validate();

            boolean hasWarning = appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getMessage().contains("同时设置了 path 和 pathPattern"));
            assertTrue(hasWarning, "应输出 WARN 日志提示 path/pathPattern 歧义");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ---- P1: getEffectivePath ----

    @Test
    void getEffectivePath_whenPathPatternNonEmpty_shouldReturnPathPattern() {
        EndpointConfig ep = endpoint("test", "POST", "/exact", "/pattern/{id}",
            "application/json", "application/json", 200, "{}");
        assertEquals("/pattern/{id}", ep.getEffectivePath());
    }

    @Test
    void getEffectivePath_whenPathPatternEmpty_shouldReturnPath() {
        EndpointConfig ep = endpoint("test", "POST", "/exact", null,
            "application/json", "application/json", 200, "{}");
        assertEquals("/exact", ep.getEffectivePath());
    }

    @Test
    void getEffectivePath_whenPathPatternOnlyWhitespace_shouldReturnPathPattern() {
        // getEffectivePath() uses isEmpty() not isBlank() — whitespace pathPattern is treated as set
        EndpointConfig ep = endpoint("test", "POST", "/exact", "   ",
            "application/json", "application/json", 200, "{}");
        assertEquals("   ", ep.getEffectivePath());
    }

    // ---- helpers ----

    private EndpointConfig endpoint(String id, String method, String path,
                                     String pathPattern, String contentType,
                                     String responseContentType, int responseStatus,
                                     String responseBody) {
        EndpointConfig ep = new EndpointConfig();
        ep.setId(id);
        ep.setMethod(method);
        ep.setPath(path);
        ep.setPathPattern(pathPattern);
        ep.setContentType(contentType);
        ep.setResponseContentType(responseContentType);
        ep.setResponseStatus(responseStatus);
        ep.setResponseBody(responseBody);
        return ep;
    }
}
