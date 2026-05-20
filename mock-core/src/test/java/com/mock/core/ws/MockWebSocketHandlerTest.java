package com.mock.core.ws;

import com.mock.core.config.WebSocketEndpointConfig;
import com.mock.core.metrics.MockMetrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockWebSocketHandlerTest {

    private MockWebSocketHandler handler;
    private MockMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new MockMetrics();
        WebSocketEndpointConfig config = new WebSocketEndpointConfig();
        config.setId("test-ws");
        config.setPath("/ws/test");
        config.setOnConnect("{\"type\":\"hello\",\"sid\":\"{{sessionId}}\"}");
        handler = new MockWebSocketHandler(config, metrics);
    }

    @Test
    void template_shouldReplaceSessionId() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("abc-123");

        String result = handler.template("{\"sid\":\"{{sessionId}}\"}", session, null);
        assertEquals("{\"sid\":\"abc-123\"}", result);
    }

    @Test
    void template_shouldReplaceMessage() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("abc-123");

        String result = handler.template("{\"echo\":\"{{message}}\"}", session, "hello");
        assertEquals("{\"echo\":\"hello\"}", result);
    }

    @Test
    void template_shouldHandleNullRaw() {
        WebSocketSession session = mock(WebSocketSession.class);
        assertNull(handler.template(null, session, "msg"));
    }

    @Test
    void template_shouldReturnUnchangedWhenNoPlaceholders() {
        WebSocketSession session = mock(WebSocketSession.class);
        String raw = "plain text without placeholders";
        assertEquals(raw, handler.template(raw, session, null));
    }

    @Test
    void template_shouldReplaceMultiplePlaceholders() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sid-1");

        String result = handler.template(
            "{{\"sid\":\"{{sessionId}}\",\"msg\":\"{{message}}\"}}", session, "hi");
        assertEquals("{{\"sid\":\"sid-1\",\"msg\":\"hi\"}}", result);
    }
}
