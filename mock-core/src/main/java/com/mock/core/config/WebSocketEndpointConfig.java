package com.mock.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * WebSocket Mock 端点配置模型。
 */
public class WebSocketEndpointConfig {

    private String id;
    private String description;
    private String path;
    private String onConnect;
    private String onConnectFile;
    private String onDisconnect;
    private String onDisconnectFile;
    private java.util.List<MessageHandler> messageHandlers = new ArrayList<>();
    private HeartbeatConfig heartbeat;
    private long delay = 0;

    public static class MessageHandler {
        private String pattern;
        private String response;

        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }
        public String getResponse() { return response; }
        public void setResponse(String response) { this.response = response; }
    }

    public static class HeartbeatConfig {
        private long interval = 30000;  // 毫秒
        private String message = "{\"type\":\"ping\"}";

        public long getInterval() { return interval; }
        public void setInterval(long interval) { this.interval = interval; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    // ---- getters / setters ----

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getOnConnect() { return onConnect; }
    public void setOnConnect(String onConnect) { this.onConnect = onConnect; }

    public String getOnConnectFile() { return onConnectFile; }
    public void setOnConnectFile(String onConnectFile) { this.onConnectFile = onConnectFile; }

    public String getOnDisconnect() { return onDisconnect; }
    public void setOnDisconnect(String onDisconnect) { this.onDisconnect = onDisconnect; }

    public String getOnDisconnectFile() { return onDisconnectFile; }
    public void setOnDisconnectFile(String onDisconnectFile) { this.onDisconnectFile = onDisconnectFile; }

    public List<MessageHandler> getMessageHandlers() { return messageHandlers; }
    public void setMessageHandlers(List<MessageHandler> messageHandlers) { this.messageHandlers = messageHandlers; }

    public HeartbeatConfig getHeartbeat() { return heartbeat; }
    public void setHeartbeat(HeartbeatConfig heartbeat) { this.heartbeat = heartbeat; }

    public long getDelay() { return delay; }
    public void setDelay(long delay) { this.delay = delay; }
}
