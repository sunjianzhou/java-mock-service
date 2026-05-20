package com.mock.core.ws;

import com.mock.core.config.WebSocketEndpointConfig;
import com.mock.core.metrics.MockMetrics;
import com.mock.core.util.BuiltinTemplateVars;
import com.mock.core.util.ResourceReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Mock WebSocket 处理器：连接时发送欢迎消息，根据配置的模式匹配回显消息。
 */
public class MockWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MockWebSocketHandler.class);
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(sessionId|message)\\}\\}");

    private final WebSocketEndpointConfig config;
    private final MockMetrics metrics;
    private final ResourceReader resourceReader;
    private final List<java.util.regex.Pattern> compiledPatterns;

    public MockWebSocketHandler(WebSocketEndpointConfig config, MockMetrics metrics,
                                 ResourceReader resourceReader) {
        this.config = config;
        this.metrics = metrics;
        this.resourceReader = resourceReader;
        this.compiledPatterns = new ArrayList<>();
        if (config.getMessageHandlers() != null) {
            for (WebSocketEndpointConfig.MessageHandler handler : config.getMessageHandlers()) {
                try {
                    compiledPatterns.add(Pattern.compile(handler.getPattern()));
                } catch (PatternSyntaxException e) {
                    log.warn("Invalid regex pattern for [{}]: {} — {}", config.getId(),
                        handler.getPattern(), e.getMessage());
                }
            }
        }
    }

    public WebSocketEndpointConfig getConfig() {
        return config;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        log.info("WebSocket connected: {} -> {} (id={})",
            session.getId(), config.getPath(), config.getId());
        metrics.incrementWsSessions();

        // 1) 连接欢迎消息
        Mono<Void> welcome = sendIfPresent(session, resolveWsContent(config.getOnConnect(), config.getOnConnectFile()));

        // 2) 心跳（可选）
        Mono<Void> heartbeat = Mono.empty();
        if (config.getHeartbeat() != null && config.getHeartbeat().getInterval() > 0) {
            heartbeat = Flux.interval(Duration.ofMillis(config.getHeartbeat().getInterval()))
                .flatMap(tick -> session.send(Mono.just(
                    session.textMessage(template(config.getHeartbeat().getMessage(), session, null))
                )))
                .then();
        }

        // 3) 消息处理
        Mono<Void> messages = session.receive()
            .flatMap(msg -> handleMessage(session, msg))
            .then();

        // 4) 断开处理（容错）
        Mono<Void> disconnect = sendIfPresent(session, resolveWsContent(config.getOnDisconnect(), config.getOnDisconnectFile()))
            .onErrorResume(e -> {
                log.warn("WebSocket disconnect message failed: {}", e.getMessage());
                return Mono.empty();
            });

        return welcome.then(Mono.firstWithSignal(messages, heartbeat))
            .then(disconnect)
            .doFinally(s -> {
                metrics.decrementWsSessions();
                log.info("WebSocket disconnected: {} -> {}",
                    session.getId(), config.getPath());
            })
            .onErrorResume(e -> {
                log.warn("WebSocket error for {}: {}", config.getId(), e.getMessage());
                return Mono.empty();
            });
    }

    private Mono<Void> handleMessage(WebSocketSession session, WebSocketMessage msg) {
        String payload = msg.getPayloadAsText();
        log.debug("WS message [{}]: {}", config.getId(), payload);

        for (int i = 0; i < compiledPatterns.size() && i < config.getMessageHandlers().size(); i++) {
            WebSocketEndpointConfig.MessageHandler handler = config.getMessageHandlers().get(i);
            java.util.regex.Pattern compiled = compiledPatterns.get(i);
            if (compiled == null) continue;
            boolean matched = compiled.matcher(payload).matches();
            if (matched) {
                String response = template(handler.getResponse(), session, payload);
                Mono<Void> send = session.send(Mono.just(session.textMessage(response)));
                if (config.getDelay() > 0) {
                    send = send.delaySubscription(Duration.ofMillis(config.getDelay()));
                }
                return send;
            }
        }
        return Mono.empty();
    }

    private Mono<Void> sendIfPresent(WebSocketSession session, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Mono.empty();
        }
        String msg = template(raw, session, null);
        return session.send(Mono.just(session.textMessage(msg)));
    }

    /** 解析 WebSocket 消息内容：inline 文本优先，file 兜底（Fix-9：使用注入的 ResourceReader）。 */
    private String resolveWsContent(String inline, String file) {
        if (inline != null && !inline.trim().isEmpty()) {
            return inline;
        }
        if (file != null && !file.trim().isEmpty()) {
            return resourceReader.readFile(file);
        }
        return null;
    }

    String template(String raw, WebSocketSession session, String message) {
        if (raw == null || !raw.contains("{{")) {
            return raw;
        }
        Matcher matcher = TEMPLATE_PATTERN.matcher(raw);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = "";
            if ("sessionId".equals(key)) {
                replacement = session.getId();
            } else if ("message".equals(key) && message != null) {
                replacement = message.replace("\\", "\\\\").replace("$", "\\$");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        // Fix-7: 再替换内置变量（{{now}} / {{uuid}} / {{seq}}）
        return BuiltinTemplateVars.apply(result.toString());
    }
}
