package com.mock.core.protocol;

import com.mock.core.config.EndpointConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XmlAdapterTest {

    private final XmlAdapter adapter = new XmlAdapter();

    @Test
    void supports_shouldMatchXml() {
        EndpointConfig config = new EndpointConfig();
        config.setContentType("text/xml");
        assertTrue(adapter.supports(config));
    }

    @Test
    void supports_shouldNotMatchJson() {
        EndpointConfig config = new EndpointConfig();
        config.setContentType("application/json");
        assertFalse(adapter.supports(config));
    }

    @Test
    void supports_shouldNotMatchFormUrlEncoded() {
        EndpointConfig config = new EndpointConfig();
        config.setContentType("application/x-www-form-urlencoded");
        assertFalse(adapter.supports(config));
    }

    // ---- XML/SOAP body 解析 ----

    @Test
    void extractParams_shouldReturnEmptyMap_whenEmptyBody() {
        ServerWebExchange exchange = mockExchangeWithBody("");

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> assertTrue(map.isEmpty()))
            .verifyComplete();
    }

    @Test
    void extractParams_shouldExtractLeafElementsFromSoapBody() {
        String soapBody = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body>"
            + "<NACAOREQUEST>"
            + "<JGMC>测试机构</JGMC>"
            + "<ZZJGDM>12345678-9</ZZJGDM>"
            + "</NACAOREQUEST>"
            + "</soap:Body>"
            + "</soap:Envelope>";

        ServerWebExchange exchange = mockExchangeWithBody(soapBody);

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> {
                assertEquals(2, map.size());
                assertEquals("测试机构", map.get("JGMC"));
                assertEquals("12345678-9", map.get("ZZJGDM"));
            })
            .verifyComplete();
    }

    @Test
    void extractParams_shouldExtractPlainXmlElements() {
        String xml = "<Request>"
            + "<enterpriseName>测试企业</enterpriseName>"
            + "<enterpriseMark>MARK001</enterpriseMark>"
            + "<signature>SIG_DATA</signature>"
            + "</Request>";

        ServerWebExchange exchange = mockExchangeWithBody(xml);

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> {
                assertEquals(3, map.size());
                assertEquals("测试企业", map.get("enterpriseName"));
                assertEquals("MARK001", map.get("enterpriseMark"));
                assertEquals("SIG_DATA", map.get("signature"));
            })
            .verifyComplete();
    }

    @Test
    void extractParams_shouldSkipNestedParentElements() {
        String xml = "<Envelope>"
            + "<Body>"
            + "<Parent>"
            + "<Child1>value1</Child1>"
            + "<Child2>value2</Child2>"
            + "</Parent>"
            + "</Body>"
            + "</Envelope>";

        ServerWebExchange exchange = mockExchangeWithBody(xml);

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> {
                assertEquals(2, map.size());
                assertEquals("value1", map.get("Child1"));
                assertEquals("value2", map.get("Child2"));
                // Parent, Body, Envelope have element children → skipped
            })
            .verifyComplete();
    }

    @Test
    void extractParams_shouldReturnEmptyMap_whenMalformedXml() {
        ServerWebExchange exchange = mockExchangeWithBody("<open>no close");

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> assertTrue(map.isEmpty()))
            .verifyComplete();
    }

    // ---- parseXmlToParams 直接测试 ----

    @Test
    void parseXmlToParams_shouldExtractLeafElements() {
        String xml = "<root><name>张三</name><age>30</age></root>";
        Map<String, String> params = adapter.parseXmlToParams(xml);
        assertEquals("张三", params.get("name"));
        assertEquals("30", params.get("age"));
    }

    @Test
    void parseXmlToParams_shouldReturnEmptyMap_whenInvalidXml() {
        Map<String, String> params = adapter.parseXmlToParams("<<<bad>>>");
        assertTrue(params.isEmpty());
    }

    // ---- helpers ----

    private ServerWebExchange mockExchangeWithBody(String body) {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(exchange.getRequest()).thenReturn(request);

        if (body != null && !body.isEmpty()) {
            DataBuffer buffer = new DefaultDataBufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
            when(request.getBody()).thenReturn(Flux.just(buffer));
        } else {
            when(request.getBody()).thenReturn(Flux.empty());
        }

        return exchange;
    }
}
