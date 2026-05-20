package com.mock.core.handler;

import com.mock.core.config.EndpointConfig;
import com.mock.core.config.MockConfigProperties;
import com.mock.core.metrics.MockMetrics;
import com.mock.core.protocol.FormUrlEncodedAdapter;
import com.mock.core.protocol.JsonAdapter;
import com.mock.core.protocol.ProtocolAdapter;
import com.mock.core.protocol.XmlAdapter;
import com.mock.core.record.InMemoryRecordingStore;
import com.mock.core.record.RecordingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockRequestHandlerTest {

    private MockRequestHandler handler;
    private RecordingStore recordingStore;
    private MockMetrics metrics;

    @BeforeEach
    void setUp() {
        recordingStore = new InMemoryRecordingStore();
        metrics = new MockMetrics();
        handler = new MockRequestHandler(Arrays.asList(
            new FormUrlEncodedAdapter(),
            new JsonAdapter(),
            new XmlAdapter()
        ), recordingStore, metrics, "");
    }

    // ---- X-Mock-Endpoint 响应头 ----

    @Test
    void handle_shouldSetXMockEndpointHeader() {
        EndpointConfig config = jsonEndpoint("zt-id-card", "{\"status\":\"ok\"}");
        ServerRequest request = mockPost("/mock/ias/zt/id-card");

        Mono<ServerResponse> responseMono = handler.handle(request, config);

        StepVerifier.create(responseMono)
            .assertNext(response ->
                assertEquals("zt-id-card",
                    response.headers().getFirst("X-Mock-Endpoint")))
            .verifyComplete();
    }

    // ---- Content-Type 正确性 ----

    @Test
    void handle_shouldSetJsonContentType() {
        EndpointConfig config = jsonEndpoint("test", "{}");
        ServerRequest request = mockPost("/test");

        Mono<ServerResponse> responseMono = handler.handle(request, config);

        StepVerifier.create(responseMono)
            .assertNext(response ->
                assertEquals(MediaType.APPLICATION_JSON,
                    response.headers().getContentType()))
            .verifyComplete();
    }

    @Test
    void handle_shouldSetXmlContentType() {
        EndpointConfig config = new EndpointConfig();
        config.setId("test-nacao");
        config.setMethod("POST");
        config.setPath("/service/nacao");
        config.setResponseContentType("text/xml");
        config.setResponseStatus(200);
        config.setResponseBody("<xml/>");
        ServerRequest request = mockPost("/service/nacao");

        Mono<ServerResponse> responseMono = handler.handle(request, config);

        StepVerifier.create(responseMono)
            .assertNext(response -> {
                MediaType contentType = response.headers().getContentType();
                assertNotNull(contentType);
                assertTrue(contentType.toString().contains("text/xml"));
            })
            .verifyComplete();
    }

    // ---- 参数校验: 无 validation 配置 → 200 ----

    @Test
    void handle_shouldReturn200_whenNoValidationConfigured() {
        EndpointConfig config = jsonEndpoint("test-no-validation", "{\"status\":\"ok\"}");
        ServerRequest request = mockPost("/test");

        Mono<ServerResponse> responseMono = handler.handle(request, config);

        StepVerifier.create(responseMono)
            .assertNext(response -> assertEquals(200, response.rawStatusCode()))
            .verifyComplete();
    }

    // ---- 参数校验: 必填参数全部存在 → 200 ----

    @Test
    void handle_shouldReturn200_whenAllRequiredParamsPresent() {
        // 使用 mock adapter 精确控制提取到的参数
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(buildMap("app_id", "APP001", "biz_type", "A001")));

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-pass");
        config.setMethod("POST");
        config.setPath("/test");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{\"result\":\"ok\"}");

        EndpointConfig.ValidationConfig validation = new EndpointConfig.ValidationConfig();
        validation.setRequiredParams(Arrays.asList("app_id", "biz_type"));
        config.setValidation(validation);

        ServerRequest request = mockPost("/test");

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(200, response.rawStatusCode()))
            .verifyComplete();
    }

    // ---- 参数校验: 缺少必填参数 → 400 ----

    @Test
    void handle_shouldReturn400_whenMissingRequiredParam() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.singletonMap("app_id", "APP001"))); // 缺少 biz_type

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-fail");
        config.setMethod("POST");
        config.setPath("/test");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{\"result\":\"ok\"}");

        EndpointConfig.ValidationConfig validation = new EndpointConfig.ValidationConfig();
        validation.setRequiredParams(Arrays.asList("app_id", "biz_type"));
        validation.setErrorStatus(400);
        validation.setErrorBody("{\"error_no\":\"1001\"}");
        config.setValidation(validation);

        ServerRequest request = mockPost("/test");

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(400, response.rawStatusCode()))
            .verifyComplete();
    }

    // ---- 参数校验: requiredParams 包含空值 → 视为缺失 ----

    @Test
    void handle_shouldTreatEmptyValueAsMissing() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.singletonMap("app_id", ""))); // 值为空字符串

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-empty");
        config.setMethod("POST");
        config.setPath("/test");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{}");

        EndpointConfig.ValidationConfig validation = new EndpointConfig.ValidationConfig();
        validation.setRequiredParams(Arrays.asList("app_id"));
        validation.setErrorStatus(400);
        validation.setErrorBody("{\"error_no\":\"1001\"}");
        config.setValidation(validation);

        ServerRequest request = mockPost("/test");

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(400, response.rawStatusCode()))
            .verifyComplete();
    }

    // ---- 参数校验: 自定义 errorStatus 和 errorBody ----

    @Test
    void handle_shouldUseCustomErrorStatusAndBody() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.emptyMap()));

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-custom-error");
        config.setMethod("POST");
        config.setPath("/test");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{}");

        EndpointConfig.ValidationConfig validation = new EndpointConfig.ValidationConfig();
        validation.setRequiredParams(Arrays.asList("name"));
        validation.setErrorStatus(422);
        validation.setErrorBody("{\"custom\":\"error\"}");
        config.setValidation(validation);

        ServerRequest request = mockPost("/test");

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(422, response.rawStatusCode()))
            .verifyComplete();
    }

    // ---- 模板引擎: 占位符替换 ----

    @Test
    void applyTemplate_shouldReplaceSinglePlaceholder() {
        Map<String, String> params = Collections.singletonMap("name", "张三");
        String result = handler.applyTemplate("Hello {{param.name}}!", params);
        assertEquals("Hello 张三!", result);
    }

    @Test
    void applyTemplate_shouldReplaceMultiplePlaceholders() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("name", "张三");
        params.put("idNo", "123456789012345678");
        String template = "{\"name\":\"{{param.name}}\",\"idNo\":\"{{param.idNo}}\"}";
        String result = handler.applyTemplate(template, params);
        assertEquals("{\"name\":\"张三\",\"idNo\":\"123456789012345678\"}", result);
    }

    @Test
    void applyTemplate_shouldReplaceMissingParamWithEmptyString() {
        Map<String, String> params = Collections.emptyMap();
        String result = handler.applyTemplate("{{param.missing}}", params);
        assertEquals("", result);
    }

    @Test
    void applyTemplate_shouldReturnUnchanged_whenNoPlaceholder() {
        Map<String, String> params = Collections.singletonMap("name", "test");
        String template = "{\"status\":\"ok\"}";
        String result = handler.applyTemplate(template, params);
        assertEquals("{\"status\":\"ok\"}", result);
    }

    @Test
    void applyTemplate_shouldReturnNull_whenInputNull() {
        assertNull(handler.applyTemplate(null, Collections.emptyMap()));
    }

    @Test
    void applyTemplate_shouldReplaceSamePlaceholderMultipleTimes() {
        Map<String, String> params = Collections.singletonMap("name", "张三");
        String result = handler.applyTemplate("{{param.name}} and {{param.name}}", params);
        assertEquals("张三 and 张三", result);
    }

    // ---- 模板引擎: 通过 handle() 端到端验证 ----

    @Test
    void handle_shouldApplyTemplateToResponseBody() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.singletonMap("name", "李四")));

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-template");
        config.setMethod("POST");
        config.setPath("/test");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{\"result\":\"ok\",\"user\":\"{{param.name}}\"}");

        ServerRequest request = mockPost("/test");

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(200, response.rawStatusCode()))
            .verifyComplete();
    }

    @Test
    void handle_shouldApplyTemplateToErrorBody() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.emptyMap())); // 缺少必填参数

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-template-error");
        config.setMethod("POST");
        config.setPath("/test");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{}");

        EndpointConfig.ValidationConfig validation = new EndpointConfig.ValidationConfig();
        validation.setRequiredParams(Collections.singletonList("app_id"));
        validation.setErrorStatus(400);
        validation.setErrorBody("{\"error_no\":\"1001\",\"missing\":\"{{param.app_id}}\"}");
        config.setValidation(validation);

        ServerRequest request = mockPost("/test");

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(400, response.rawStatusCode()))
            .verifyComplete();
    }

    // ---- 格式校验: 参数值匹配正则 → 200 ----

    @Test
    void handle_shouldReturn200_whenFormatValid() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.singletonMap("idNo", "123456789012345678")));

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-format-ok");
        config.setMethod("POST");
        config.setPath("/test");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{}");

        EndpointConfig.ValidationConfig validation = new EndpointConfig.ValidationConfig();
        EndpointConfig.ParamRule rule = new EndpointConfig.ParamRule();
        rule.setName("idNo");
        rule.setPattern("^\\d{18}$");
        rule.setErrorMessage("idNo must be 18 digits");
        validation.setParamRules(Collections.singletonList(rule));
        config.setValidation(validation);

        ServerRequest request = mockPost("/test");

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(200, response.rawStatusCode()))
            .verifyComplete();
    }

    // ---- 格式校验: 参数值不匹配正则 → 400 ----

    @Test
    void handle_shouldReturn400_whenFormatInvalid() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.singletonMap("idNo", "abc"))); // 非18位数字

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-format-fail");
        config.setMethod("POST");
        config.setPath("/test");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{}");

        EndpointConfig.ValidationConfig validation = new EndpointConfig.ValidationConfig();
        EndpointConfig.ParamRule rule = new EndpointConfig.ParamRule();
        rule.setName("idNo");
        rule.setPattern("^\\d{18}$");
        rule.setErrorMessage("idNo must be 18 digits");
        validation.setParamRules(Collections.singletonList(rule));
        validation.setErrorStatus(400);
        validation.setErrorBody("{\"error_no\":\"1002\"}");
        config.setValidation(validation);

        ServerRequest request = mockPost("/test");

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(400, response.rawStatusCode()))
            .verifyComplete();
    }

    // ---- 格式校验: 参数不存在时跳过格式检查 ----

    @Test
    void handle_shouldSkipFormatCheck_whenParamAbsent() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.emptyMap())); // idNo 不存在

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-format-skip");
        config.setMethod("POST");
        config.setPath("/test");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{}");

        // 有 requiredParams 确保 idNo 必填，但 params 里没有 → required check 先触发
        // 这里我们不设 requiredParams，只设 paramRules，params 为空 → 跳过格式检查
        EndpointConfig.ValidationConfig validation = new EndpointConfig.ValidationConfig();
        EndpointConfig.ParamRule rule = new EndpointConfig.ParamRule();
        rule.setName("idNo");
        rule.setPattern("^\\d{18}$");
        validation.setParamRules(Collections.singletonList(rule));
        config.setValidation(validation);

        ServerRequest request = mockPost("/test");

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(200, response.rawStatusCode()))
            .verifyComplete();
    }

    // ---- 格式校验: 必填检查优先于格式检查 ----

    @Test
    void handle_shouldCheckRequiredBeforeFormat() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.emptyMap())); // 缺少必填 name

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-order");
        config.setMethod("POST");
        config.setPath("/test");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{}");

        EndpointConfig.ValidationConfig validation = new EndpointConfig.ValidationConfig();
        validation.setRequiredParams(Collections.singletonList("name"));
        EndpointConfig.ParamRule rule = new EndpointConfig.ParamRule();
        rule.setName("name");
        rule.setPattern("^.{2,10}$");
        validation.setParamRules(Collections.singletonList(rule));
        validation.setErrorStatus(400);
        validation.setErrorBody("{\"error_no\":\"1001\"}");
        config.setValidation(validation);

        ServerRequest request = mockPost("/test");

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> {
                assertEquals(400, response.rawStatusCode()); // 因必填缺失而 400，非格式
            })
            .verifyComplete();
    }

    // ---- 路径变量: pathPattern {var} 提取与合并 ----

    @Test
    void handle_shouldMergePathVariablesWithBodyParams() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.singletonMap("appId", "from-body")));

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-path-vars");
        config.setMethod("POST");
        config.setPathPattern("/api/users/{type}/sync/{appId}");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{\"type\":\"{{param.type}}\",\"appId\":\"{{param.appId}}\"}");

        // 路径变量: type=1, appId=from-path
        // body 参数: appId=from-body → body 覆盖路径变量
        Map<String, String> pathVars = new LinkedHashMap<>();
        pathVars.put("type", "1");
        pathVars.put("appId", "from-path");
        ServerRequest request = mockPostWithPathVars("/api/users/1/sync/from-path", pathVars);

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(200, response.rawStatusCode()))
            .verifyComplete();
    }

    @Test
    void handle_shouldUsePathVariables_whenNoBodyParams() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        // 没有 body 参数
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.emptyMap()));

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-path-only");
        config.setMethod("POST");
        config.setPathPattern("/vals-ap/identity/{type}/sync/{appId}");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{\"version\":\"1.0.0\",\"type\":\"{{param.type}}\"}");

        Map<String, String> pathVars = new LinkedHashMap<>();
        pathVars.put("type", "2");
        pathVars.put("appId", "APP_XYZ");
        ServerRequest request = mockPostWithPathVars("/vals-ap/identity/2/sync/APP_XYZ", pathVars);

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(200, response.rawStatusCode()))
            .verifyComplete();
    }

    // ---- 响应延迟 ----

    @Test
    void handle_shouldApplyResponseDelay() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.emptyMap()));

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-delay");
        config.setMethod("POST");
        config.setPath("/test");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{\"result\":\"ok\"}");
        config.setResponseDelay(100); // 100ms 延迟

        ServerRequest request = mockPost("/test");

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(200, response.rawStatusCode()))
            .verifyComplete();
    }

    // ---- 条件响应 ----

    @Test
    void handle_shouldReturnMatchedCase_whenConditionalResponse() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.singletonMap("status", "0")));

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig.ConditionalResponse cond = new EndpointConfig.ConditionalResponse();
        cond.setParam("status");
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("0", "{\"result\":\"matched-zero\"}");
        cases.put("1", "{\"result\":\"matched-one\"}");
        cond.setCases(cases);
        cond.setDefaultResponse("{\"result\":\"default\"}");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-cond");
        config.setMethod("POST");
        config.setPath("/test");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{\"result\":\"fallback\"}");
        config.setConditionalResponse(cond);

        ServerRequest request = mockPost("/test");

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(200, response.rawStatusCode()))
            .verifyComplete();
    }

    @Test
    void handle_shouldFallbackToDefault_whenNoCaseMatch() {
        ProtocolAdapter mockAdapter = mock(ProtocolAdapter.class);
        when(mockAdapter.supports(any())).thenReturn(true);
        when(mockAdapter.extractParams(any()))
            .thenReturn(Mono.just(Collections.singletonMap("status", "99")));

        MockRequestHandler handlerWithMock = new MockRequestHandler(
            Collections.singletonList(mockAdapter), recordingStore, metrics, "");

        EndpointConfig.ConditionalResponse cond = new EndpointConfig.ConditionalResponse();
        cond.setParam("status");
        cond.setCases(Collections.singletonMap("0", "{\"result\":\"zero\"}"));
        cond.setDefaultResponse("{\"result\":\"default\"}");

        EndpointConfig config = new EndpointConfig();
        config.setId("test-cond-default");
        config.setMethod("POST");
        config.setPath("/test");
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody("{\"result\":\"fallback\"}");
        config.setConditionalResponse(cond);

        ServerRequest request = mockPost("/test");

        StepVerifier.create(handlerWithMock.handle(request, config))
            .assertNext(response -> assertEquals(200, response.rawStatusCode()))
            .verifyComplete();
    }

    // ---- 管理端点 listRoutes ----

    @Test
    void listRoutes_shouldReturn200AndJsonContentType() {
        MockConfigProperties config = new MockConfigProperties();
        EndpointConfig ep = new EndpointConfig();
        ep.setId("test-1");
        ep.setMethod("POST");
        ep.setPath("/test-1");
        ep.setDescription("测试端点1");
        config.setEndpoints(Collections.singletonList(ep));

        Mono<ServerResponse> responseMono = handler.listRoutes(config);

        StepVerifier.create(responseMono)
            .assertNext(response -> {
                assertEquals(200, response.rawStatusCode());
                assertEquals(MediaType.APPLICATION_JSON,
                    response.headers().getContentType());
            })
            .verifyComplete();
    }

    // ---- helpers ----

    private EndpointConfig jsonEndpoint(String id, String body) {
        EndpointConfig config = new EndpointConfig();
        config.setId(id);
        config.setMethod("POST");
        config.setPath("/" + id);
        config.setContentType("application/json");
        config.setResponseContentType("application/json");
        config.setResponseStatus(200);
        config.setResponseBody(body);
        return config;
    }

    private ServerRequest mockPost(String path) {
        ServerRequest request = mock(ServerRequest.class);
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest httpRequest = mock(ServerHttpRequest.class);

        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.uri()).thenReturn(URI.create(path));
        when(request.pathVariables()).thenReturn(Collections.emptyMap());
        when(request.exchange()).thenReturn(exchange);
        when(exchange.getRequest()).thenReturn(httpRequest);
        when(exchange.getFormData()).thenReturn(Mono.just(new LinkedMultiValueMap<>()));
        when(httpRequest.getBody()).thenReturn(Flux.empty());

        return request;
    }

    private Map<String, String> buildMap(String k1, String v1, String k2, String v2) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private ServerRequest mockPostWithPathVars(String path, Map<String, String> pathVars) {
        ServerRequest request = mockPost(path);
        when(request.pathVariables()).thenReturn(pathVars);
        return request;
    }
}
