package com.mock.core.protocol;

import com.mock.core.config.EndpointConfig;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FormUrlEncodedAdapterTest {

    private final FormUrlEncodedAdapter adapter = new FormUrlEncodedAdapter();

    @Test
    void supports_shouldMatchUrlEncodedForm() {
        EndpointConfig config = new EndpointConfig();
        config.setContentType("application/x-www-form-urlencoded");
        assertTrue(adapter.supports(config));
    }

    @Test
    void supports_shouldNotMatchJson() {
        EndpointConfig config = new EndpointConfig();
        config.setContentType("application/json");
        assertFalse(adapter.supports(config));
    }

    @Test
    void supports_shouldNotMatchXml() {
        EndpointConfig config = new EndpointConfig();
        config.setContentType("text/xml");
        assertFalse(adapter.supports(config));
    }

    @Test
    void extractParams_shouldExtractFormData() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("certseq", "SEQ001");
        formData.add("usernm", "测试姓名");
        formData.add("biztyp", "A001");
        when(exchange.getFormData()).thenReturn(Mono.just(formData));

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> {
                assertEquals(3, map.size());
                assertEquals("SEQ001", map.get("certseq"));
                assertEquals("测试姓名", map.get("usernm"));
                assertEquals("A001", map.get("biztyp"));
            })
            .verifyComplete();
    }

    @Test
    void extractParams_shouldJoinMultiValuedKeyWithComma() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("key", "first");
        formData.add("key", "second");
        when(exchange.getFormData()).thenReturn(Mono.just(formData));

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> assertEquals("first,second", map.get("key")))
            .verifyComplete();
    }

    @Test
    void extractParams_shouldReturnEmptyMap_whenFormDataEmpty() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        when(exchange.getFormData()).thenReturn(Mono.just(formData));

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> assertTrue(map.isEmpty()))
            .verifyComplete();
    }

    @Test
    void extractParams_shouldReturnEmptyMap_whenError() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getFormData()).thenReturn(Mono.error(new RuntimeException("parse error")));

        StepVerifier.create(adapter.extractParams(exchange))
            .assertNext(map -> assertTrue(map.isEmpty()))
            .verifyComplete();
    }
}
