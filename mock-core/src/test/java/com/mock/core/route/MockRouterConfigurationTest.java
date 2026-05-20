package com.mock.core.route;

import com.mock.core.TestMockApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0/P1: WebTestClient 端到端路由集成测试。
 * 使用 mock-core/src/test/resources/application.yml 中的 4 个测试端点。
 */
@SpringBootTest(
    classes = TestMockApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class MockRouterConfigurationTest {

    @Autowired
    private WebTestClient webTestClient;

    // ---- P0: 精确路径路由 ----

    @Test
    void exactPath_shouldReturn200AndCorrectJsonBody() {
        webTestClient.post()
            .uri("/mock/ias/zt/id-card")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("name=test&idNo=123456")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectHeader().valueEquals("X-Mock-Endpoint", "test-zt")
            .expectBody()
            .jsonPath("$.error_no").isEqualTo("0")
            .jsonPath("$.results[0].status").isEqualTo("1");
    }

    // ---- P0: pathPattern 动态路径路由 ----

    @Test
    void pathPattern_shouldMatchDynamicSegments() {
        webTestClient.post()
            .uri("/vals-ap/identity/1/sync/app123")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"bizType\":\"1\"}")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectHeader().valueEquals("X-Mock-Endpoint", "test-eid")
            .expectBody()
            .jsonPath("$.version").isEqualTo("1.0.0")
            .jsonPath("$.result").isEqualTo("0000");
    }

    @Test
    void pathPattern_shouldMatchDifferentDynamicValues() {
        webTestClient.post()
            .uri("/vals-ap/identity/99/sync/appXYZ")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-Mock-Endpoint", "test-eid");
    }

    // ---- P1: pathPattern 路径变量模板回显 ----

    @Test
    void pathPattern_shouldEchoPathVariablesInResponse() {
        webTestClient.post()
            .uri("/api/users/U123/orders/O456")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-Mock-Endpoint", "test-path-vars")
            .expectBody()
            .jsonPath("$.userId").isEqualTo("U123")
            .jsonPath("$.orderId").isEqualTo("O456");
    }

    // ---- P2: 响应延迟 ----

    @Test
    void delayedResponse_shouldReturn200() {
        webTestClient.post()
            .uri("/mock/test/delay")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.result").isEqualTo("delayed");
    }

    // ---- P2: 条件响应 ----

    @Test
    void conditionalResponse_shouldReturnMatchedCase() {
        webTestClient.post()
            .uri("/mock/test/conditional")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"status\":\"0\",\"name\":\"张三\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo("0")
            .jsonPath("$.message").isEqualTo("success")
            .jsonPath("$.data").isEqualTo("张三");
    }

    @Test
    void conditionalResponse_shouldReturnAnotherCase() {
        webTestClient.post()
            .uri("/mock/test/conditional")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"status\":\"1\",\"name\":\"李四\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo("1")
            .jsonPath("$.message").isEqualTo("fail")
            .jsonPath("$.reason").isEqualTo("李四");
    }

    @Test
    void conditionalResponse_shouldFallbackToDefault() {
        webTestClient.post()
            .uri("/mock/test/conditional")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"status\":\"unknown\",\"name\":\"王五\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo("-1")
            .jsonPath("$.value").isEqualTo("unknown");
    }

    // ---- P1: 管理端点 ----

    @Test
    void adminReload_shouldReturn200() {
        webTestClient.post()
            .uri("/mock/_admin/reload")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("ok")
            .jsonPath("$.endpoints").isNumber();
    }

    @Test
    void adminRoute_shouldReturnJsonArray() {
        webTestClient.get()
            .uri("/mock/_admin/routes")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.length()").isEqualTo(11)
            .jsonPath("$[0].id").isNotEmpty()
            .jsonPath("$[0].method").isNotEmpty();
    }

    @Test
    void adminRoute_shouldIncludePathPatternWhenSet() {
        webTestClient.get()
            .uri("/mock/_admin/routes")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$[?(@.id=='test-eid')].pathPattern").isNotEmpty();
    }

    // ---- P1: 404 行为 ----

    @Test
    void unknownPath_shouldReturn404() {
        webTestClient.get()
            .uri("/nonexistent/path")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void unmatchedPostPath_shouldReturn404() {
        webTestClient.post()
            .uri("/not/registered")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isNotFound();
    }

    // ---- P1: SOAP/XML 端点 (使用字符串匹配，避免 XPath 命名空间问题) ----

    @Test
    void soapEndpoint_shouldReturnXmlResponse() {
        webTestClient.post()
            .uri("/service/nacao")
            .contentType(MediaType.TEXT_XML)
            .bodyValue("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body><NACAOREQUEST><JGMC>测试机构</JGMC></NACAOREQUEST></soap:Body>"
                + "</soap:Envelope>")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.TEXT_XML)
            .expectHeader().valueEquals("X-Mock-Endpoint", "test-nacao")
            .expectBody(String.class)
            .consumeWith(result -> {
                String body = result.getResponseBody();
                assertNotNull(body);
                assertTrue(body.contains("soap:Envelope"));
                assertTrue(body.contains("NACAORESPONSE"));
                assertTrue(body.contains("RECODE>01<"));
            });
    }

    // ---- P3: XML body 解析 + 校验 ----

    @Test
    void xmlValidation_shouldReturn400_whenMissingRequiredElement() {
        webTestClient.post()
            .uri("/service/nacao")
            .contentType(MediaType.TEXT_XML)
            .bodyValue("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body><NACAOREQUEST><OtherField>value</OtherField></NACAOREQUEST></soap:Body>"
                + "</soap:Envelope>")
            .exchange()
            .expectStatus().isEqualTo(400)
            .expectBody()
            .jsonPath("$.error_no").isEqualTo("1001");
    }

    @Test
    void xmlValidation_shouldReturn400_whenEmptyBody() {
        webTestClient.post()
            .uri("/service/nacao")
            .contentType(MediaType.TEXT_XML)
            .bodyValue("<soap:Envelope><soap:Body/></soap:Envelope>")
            .exchange()
            .expectStatus().isEqualTo(400);
    }

    // ---- P0: 参数校验 — 全部必填参数存在 → 200 ----

    @Test
    void validation_shouldReturn200_whenAllRequiredParamsPresent() {
        webTestClient.post()
            .uri("/mock/test/validation")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("certseq=SEQ001&usernm=测试")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.error_no").isEqualTo("0")
            .jsonPath("$.error_info").isEqualTo("success");
    }

    // ---- P0: 参数校验 — 缺少必填参数 → 400 ----

    @Test
    void validation_shouldReturn400_whenMissingRequiredParam() {
        webTestClient.post()
            .uri("/mock/test/validation")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("certseq=SEQ001")  // 缺少 usernm
            .exchange()
            .expectStatus().isEqualTo(400)
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.error_no").isEqualTo("1001");
    }

    @Test
    void validation_shouldReturn400_whenAllParamsMissing() {
        webTestClient.post()
            .uri("/mock/test/validation")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("other=value")  // 完全缺少 certseq 和 usernm
            .exchange()
            .expectStatus().isEqualTo(400)
            .expectBody()
            .jsonPath("$.error_no").isEqualTo("1001");
    }

    // ---- P1: 模板引擎 — 占位符替换 ----

    @Test
    void template_shouldReplacePlaceholdersWithParamValues() {
        webTestClient.post()
            .uri("/mock/test/template")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("name=张三&idNo=123456789012345678")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.name").isEqualTo("张三")
            .jsonPath("$.idNo").isEqualTo("123456789012345678");
    }

    @Test
    void template_shouldReplaceMissingParamWithEmptyString() {
        webTestClient.post()
            .uri("/mock/test/template")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("name=张三")  // 缺少 idNo
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.name").isEqualTo("张三")
            .jsonPath("$.idNo").isEmpty();
    }

    @Test
    void template_shouldWorkInErrorBody() {
        webTestClient.post()
            .uri("/mock/test/template")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("other=value")  // 缺少必填 name
            .exchange()
            .expectStatus().isEqualTo(400)
            .expectBody()
            .jsonPath("$.missing").isEmpty(); // name 参数缺失，模板替换为空
    }

    // ---- P2: 格式校验 — 参数值匹配正则 → 200 ----

    @Test
    void formatCheck_shouldReturn200_whenPatternMatches() {
        webTestClient.post()
            .uri("/mock/test/format")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("idNo=123456789012345678")  // 18位数字
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.error_no").isEqualTo("0")
            .jsonPath("$.idNo").isEqualTo("123456789012345678");
    }

    // ---- P2: 格式校验 — 参数值不匹配正则 → 400 ----

    @Test
    void formatCheck_shouldReturn400_whenPatternNotMatch() {
        webTestClient.post()
            .uri("/mock/test/format")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("idNo=abc123")  // 非18位数字
            .exchange()
            .expectStatus().isEqualTo(400)
            .expectBody()
            .jsonPath("$.error_no").isEqualTo("1002");
    }

    @Test
    void formatCheck_shouldReturn400_whenTooShort() {
        webTestClient.post()
            .uri("/mock/test/format")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("idNo=123")  // 仅3位
            .exchange()
            .expectStatus().isEqualTo(400)
            .expectBody()
            .jsonPath("$.error_no").isEqualTo("1002");
    }

    // ---- P0: 无 validation 配置的端点不受影响 ----

    @Test
    void noValidation_shouldStillReturn200() {
        webTestClient.post()
            .uri("/mock/ias/zt/id-card")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("any=value")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-Mock-Endpoint", "test-zt");
    }

    // ---- P2: WSDL GET 端点 ----

    @Test
    void wsdlEndpoint_shouldReturnXml() {
        webTestClient.get()
            .uri("/mock/wsdl/nacao")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.TEXT_XML)
            .expectHeader().valueEquals("X-Mock-Endpoint", "test-wsdl")
            .expectBody(String.class)
            .consumeWith(result -> {
                String body = result.getResponseBody();
                assertNotNull(body);
                assertTrue(body.contains("wsdl:definitions"));
            });
    }

    // ---- P2: 录制/回放管理端点 ----

    @Test
    void recordingStart_shouldReturn200() {
        webTestClient.post()
            .uri("/mock/_admin/record/start")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.recording").isEqualTo(true);
    }

    @Test
    void recordingStartStop_shouldReturnCount() {
        webTestClient.post()
            .uri("/mock/_admin/record/start")
            .exchange()
            .expectStatus().isOk();

        webTestClient.post()
            .uri("/mock/_admin/record/stop")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.recording").isEqualTo(false)
            .jsonPath("$.count").isNumber();
    }

    @Test
    void replayStartStop_shouldReturn200() {
        webTestClient.post()
            .uri("/mock/_admin/replay/start")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.replaying").isEqualTo(true);

        webTestClient.post()
            .uri("/mock/_admin/replay/stop")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.replaying").isEqualTo(false);
    }

    @Test
    void listRecordings_shouldReturnJsonArray() {
        webTestClient.get()
            .uri("/mock/_admin/recordings")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.length()").isNumber();
    }

    @Test
    void clearRecordings_shouldReturn200() {
        webTestClient.delete()
            .uri("/mock/_admin/recordings")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("ok");
    }

    @Test
    void saveAndLoadRecordings_shouldReturn200() {
        webTestClient.post()
            .uri("/mock/_admin/recordings/save")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("ok");

        webTestClient.post()
            .uri("/mock/_admin/recordings/load")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("ok");
    }
}
