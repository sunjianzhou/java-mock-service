package com.mock.core.protocol;

import com.mock.core.config.EndpointConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JsonAdapterTest {

    private final JsonAdapter adapter = new JsonAdapter();

    @Test
    void supports_shouldMatchJson() {
        EndpointConfig config = new EndpointConfig();
        config.setContentType("application/json");
        assertTrue(adapter.supports(config));
    }

    @Test
    void supports_shouldNotMatchFormUrlEncoded() {
        EndpointConfig config = new EndpointConfig();
        config.setContentType("application/x-www-form-urlencoded");
        assertFalse(adapter.supports(config));
    }

    @Test
    void supports_shouldNotMatchXml() {
        EndpointConfig config = new EndpointConfig();
        config.setContentType("text/xml");
        assertFalse(adapter.supports(config));
    }

    @Test
    void extractParams_shouldExtractStringFields() {
        ServerWebExchange exchange = mockExchangeWithBody(
            "{\"app_id\":\"APP001\",\"biz_type\":\"A001\",\"user_id_info\":\"张三|123456\"}");

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> {
                assertEquals(3, map.size());
                assertEquals("APP001", map.get("app_id"));
                assertEquals("A001", map.get("biz_type"));
                assertEquals("张三|123456", map.get("user_id_info"));
            })
            .verifyComplete();
    }

    @Test
    void extractParams_shouldExtractAllValueNodes() {
        ServerWebExchange exchange = mockExchangeWithBody(
            "{\"name\":\"test\",\"count\":123,\"nested\":{\"key\":\"val\"},\"flag\":true}");

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> {
                assertEquals(4, map.size());
                assertEquals("test", map.get("name"));
                assertEquals("123", map.get("count"));
                assertEquals("true", map.get("flag"));
                assertEquals("val", map.get("nested.key"));
            })
            .verifyComplete();
    }

    @Test
    void extractParams_shouldReturnEmptyMap_whenEmptyBody() {
        ServerWebExchange exchange = mockExchangeWithBody("");

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> assertTrue(map.isEmpty()))
            .verifyComplete();
    }

    @Test
    void extractParams_shouldReturnEmptyMap_whenEmptyJsonObject() {
        ServerWebExchange exchange = mockExchangeWithBody("{}");

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> assertTrue(map.isEmpty()))
            .verifyComplete();
    }

    @Test
    void extractParams_shouldReturnEmptyMap_whenError() {
        ServerWebExchange exchange = mockExchangeWithBody("not valid json {{{");

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> assertTrue(map.isEmpty()))
            .verifyComplete();
    }

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
