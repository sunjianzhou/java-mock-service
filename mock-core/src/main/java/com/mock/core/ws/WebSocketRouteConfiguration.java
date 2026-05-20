package com.mock.core.ws;

import com.mock.core.config.MockConfigProperties;
import com.mock.core.config.ReloadableConfigHolder;
import com.mock.core.config.WebSocketEndpointConfig;
import com.mock.core.metrics.MockMetrics;
import com.mock.core.util.ResourceReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动态注册 WebSocket Mock 端点路由，支持热加载。
 */
@Configuration
public class WebSocketRouteConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebSocketRouteConfiguration.class);

    @Bean
    public HandlerMapping webSocketHandlerMapping(ReloadableConfigHolder holder,
                                                   MockMetrics metrics,
                                                   ResourceReader resourceReader) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(1);

        refreshMapping(mapping, holder.get(), metrics, resourceReader);
        holder.setOnReload(() -> refreshMapping(mapping, holder.get(), metrics, resourceReader));

        return mapping;
    }

    private void refreshMapping(SimpleUrlHandlerMapping mapping,
                                 MockConfigProperties config, MockMetrics metrics,
                                 ResourceReader resourceReader) {
        Map<String, WebSocketHandler> handlerMap = new LinkedHashMap<>();
        if (config.getWebsockets() != null) {
            for (WebSocketEndpointConfig wsConfig : config.getWebsockets()) {
                MockWebSocketHandler handler = new MockWebSocketHandler(wsConfig, metrics, resourceReader);
                handlerMap.put(wsConfig.getPath(), handler);
                log.info("Registered WebSocket endpoint: {} -> {} (id={})",
                    wsConfig.getPath(), wsConfig.getDescription(), wsConfig.getId());
            }
        }
        mapping.setUrlMap(handlerMap);
    }
}
