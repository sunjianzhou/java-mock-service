package com.mock.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * YAML 配置解析器：将 SnakeYAML 解析出的原始 Map 转换为 MockConfigProperties。
 * 供 MockRequestHandler.reload() 和 ConfigFileWatcher.reloadFromFile() 共用，
 * 消除 ~130 行的重复解析代码。
 */
public final class YamlConfigParser {

    private static final Logger log = LoggerFactory.getLogger(YamlConfigParser.class);

    // 已知的 endpoint 顶层 key，用于检测拼写错误
    private static final Set<String> KNOWN_ENDPOINT_KEYS = new HashSet<>(Arrays.asList(
        "id", "description", "method", "path", "pathPattern",
        "contentType", "responseContentType", "responseStatus",
        "responseDelay", "responseDelayMax",
        "responseBody", "responseBodyFile", "responseScript",
        "validation", "conditionalResponse"
    ));

    private static final Set<String> KNOWN_VALIDATION_KEYS = new HashSet<>(Arrays.asList(
        "requiredParams", "paramRules", "errorStatus", "errorBody", "formatErrorBody"
    ));

    private static final Set<String> KNOWN_PARAM_RULE_KEYS = new HashSet<>(Arrays.asList(
        "name", "pattern", "errorMessage"
    ));

    private static final Set<String> KNOWN_COND_KEYS = new HashSet<>(Arrays.asList(
        "param", "cases", "defaultResponse"
    ));

    private static final Set<String> KNOWN_WS_KEYS = new HashSet<>(Arrays.asList(
        "id", "description", "path", "onConnect", "onConnectFile",
        "onDisconnect", "onDisconnectFile", "delay",
        "messageHandlers", "heartbeat"
    ));

    private static final Set<String> KNOWN_MH_KEYS = new HashSet<>(Arrays.asList(
        "pattern", "response"
    ));

    private static final Set<String> KNOWN_HB_KEYS = new HashSet<>(Arrays.asList(
        "interval", "message"
    ));

    private static final Set<String> KNOWN_ROOT_KEYS = new HashSet<>(Arrays.asList(
        "endpoints", "websockets"
    ));

    private YamlConfigParser() {}

    /**
     * 从已解析的 YAML Map 构建 MockConfigProperties。
     * @param root YAML 根 Map（已通过 SnakeYAML 解析）
     * @return 构建并校验后的 MockConfigProperties
     * @throws IllegalStateException 配置缺失或校验失败
     */
    @SuppressWarnings("unchecked")
    public static MockConfigProperties parse(Map<String, Object> root) {
        Map<String, Object> mock = (Map<String, Object>) root.get("mock");
        if (mock == null) {
            throw new IllegalArgumentException("YAML 缺少 'mock' 根键");
        }

        // 检测 mock 下的未知 key
        for (String key : mock.keySet()) {
            if (!KNOWN_ROOT_KEYS.contains(key)) {
                log.warn("YAML 中 mock.{} 不是已知配置项，可能属于拼写错误", key);
            }
        }

        List<Map<String, Object>> rawEndpoints = (List<Map<String, Object>>) mock.get("endpoints");
        if (rawEndpoints == null || rawEndpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints 列表为空");
        }

        List<EndpointConfig> endpoints = new ArrayList<>();
        for (Map<String, Object> raw : rawEndpoints) {
            endpoints.add(parseEndpoint(raw));
        }

        List<WebSocketEndpointConfig> wsEndpoints = new ArrayList<>();
        if (mock.containsKey("websockets")) {
            List<Map<String, Object>> rawWsList = (List<Map<String, Object>>) mock.get("websockets");
            if (rawWsList != null) {
                for (Map<String, Object> wsRaw : rawWsList) {
                    wsEndpoints.add(parseWebSocket(wsRaw));
                }
            }
        }

        MockConfigProperties config = new MockConfigProperties();
        config.setEndpoints(endpoints);
        config.setWebsockets(wsEndpoints);
        config.validate();
        return config;
    }

    private static EndpointConfig parseEndpoint(Map<String, Object> raw) {
        EndpointConfig ep = new EndpointConfig();
        ep.setId((String) raw.get("id"));
        ep.setDescription((String) raw.get("description"));
        ep.setMethod((String) raw.get("method"));
        ep.setPath((String) raw.get("path"));
        ep.setPathPattern((String) raw.get("pathPattern"));
        ep.setContentType((String) raw.get("contentType"));
        ep.setResponseContentType((String) raw.get("responseContentType"));
        if (raw.containsKey("responseStatus")) {
            ep.setResponseStatus(((Number) raw.get("responseStatus")).intValue());
        }
        if (raw.containsKey("responseDelay")) {
            ep.setResponseDelay(((Number) raw.get("responseDelay")).longValue());
        }
        if (raw.containsKey("responseDelayMax")) {
            ep.setResponseDelayMax(((Number) raw.get("responseDelayMax")).longValue());
        }
        ep.setResponseBody((String) raw.get("responseBody"));
        ep.setResponseBodyFile((String) raw.get("responseBodyFile"));
        ep.setResponseScript((String) raw.get("responseScript"));

        // 检测未知 key
        for (String key : raw.keySet()) {
            if (!KNOWN_ENDPOINT_KEYS.contains(key)) {
                log.warn("端点 '{}' 中包含未知配置项 '{}'，可能属于拼写错误，已忽略",
                    ep.getId(), key);
            }
        }

        // validation
        Map<String, Object> valRaw = (Map<String, Object>) raw.get("validation");
        if (valRaw != null) {
            ep.setValidation(parseValidation(ep.getId(), valRaw));
        }

        // conditionalResponse
        Map<String, Object> condRaw = (Map<String, Object>) raw.get("conditionalResponse");
        if (condRaw != null) {
            ep.setConditionalResponse(parseConditional(ep.getId(), condRaw));
        }

        return ep;
    }

    private static EndpointConfig.ValidationConfig parseValidation(String epId, Map<String, Object> valRaw) {
        EndpointConfig.ValidationConfig vc = new EndpointConfig.ValidationConfig();
        if (valRaw.containsKey("requiredParams")) {
            vc.setRequiredParams((List<String>) valRaw.get("requiredParams"));
        }
        if (valRaw.containsKey("paramRules")) {
            List<Map<String, Object>> rules = (List<Map<String, Object>>) valRaw.get("paramRules");
            List<EndpointConfig.ParamRule> paramRules = new ArrayList<>();
            if (rules != null) {
                for (Map<String, Object> r : rules) {
                    EndpointConfig.ParamRule pr = new EndpointConfig.ParamRule();
                    pr.setName((String) r.get("name"));
                    String patternStr = (String) r.get("pattern");
                    pr.setPattern(patternStr);
                    if (patternStr != null && !patternStr.isEmpty()) {
                        try {
                            pr.setCompiledPattern(java.util.regex.Pattern.compile(patternStr));
                        } catch (java.util.regex.PatternSyntaxException e) {
                            log.warn("端点 '{}' 的 paramRule '{}' 正则无效: {} — {}",
                                epId, pr.getName(), patternStr, e.getMessage());
                        }
                    }
                    if (r.containsKey("errorMessage")) {
                        pr.setErrorMessage((String) r.get("errorMessage"));
                    }
                    paramRules.add(pr);
                    for (String k : r.keySet()) {
                        if (!KNOWN_PARAM_RULE_KEYS.contains(k)) {
                            log.warn("端点 '{}' 的 paramRules 中包含未知配置项 '{}'，已忽略", epId, k);
                        }
                    }
                }
            }
            vc.setParamRules(paramRules);
        }
        if (valRaw.containsKey("errorStatus")) {
            vc.setErrorStatus(((Number) valRaw.get("errorStatus")).intValue());
        }
        vc.setErrorBody((String) valRaw.get("errorBody"));
        if (valRaw.containsKey("formatErrorBody")) {
            vc.setFormatErrorBody((String) valRaw.get("formatErrorBody"));
        }

        for (String key : valRaw.keySet()) {
            if (!KNOWN_VALIDATION_KEYS.contains(key)) {
                log.warn("端点 '{}' 的 validation 中包含未知配置项 '{}'，已忽略", epId, key);
            }
        }
        return vc;
    }

    private static EndpointConfig.ConditionalResponse parseConditional(String epId, Map<String, Object> condRaw) {
        EndpointConfig.ConditionalResponse cr = new EndpointConfig.ConditionalResponse();
        cr.setParam((String) condRaw.get("param"));
        Map<String, Object> casesRaw = (Map<String, Object>) condRaw.get("cases");
        if (casesRaw != null) {
            Map<String, String> cases = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : casesRaw.entrySet()) {
                cases.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : "");
            }
            cr.setCases(cases);
        }
        cr.setDefaultResponse((String) condRaw.get("defaultResponse"));

        for (String key : condRaw.keySet()) {
            if (!KNOWN_COND_KEYS.contains(key)) {
                log.warn("端点 '{}' 的 conditionalResponse 中包含未知配置项 '{}'，已忽略", epId, key);
            }
        }
        return cr;
    }

    private static WebSocketEndpointConfig parseWebSocket(Map<String, Object> wsRaw) {
        WebSocketEndpointConfig ws = new WebSocketEndpointConfig();
        ws.setId((String) wsRaw.get("id"));
        ws.setDescription((String) wsRaw.get("description"));
        ws.setPath((String) wsRaw.get("path"));
        ws.setOnConnect((String) wsRaw.get("onConnect"));
        ws.setOnConnectFile((String) wsRaw.get("onConnectFile"));
        ws.setOnDisconnect((String) wsRaw.get("onDisconnect"));
        ws.setOnDisconnectFile((String) wsRaw.get("onDisconnectFile"));
        if (wsRaw.containsKey("delay")) {
            ws.setDelay(((Number) wsRaw.get("delay")).longValue());
        }

        List<Map<String, Object>> rawMhList = (List<Map<String, Object>>) wsRaw.get("messageHandlers");
        if (rawMhList != null) {
            List<WebSocketEndpointConfig.MessageHandler> mhList = new ArrayList<>();
            for (Map<String, Object> mhRaw : rawMhList) {
                WebSocketEndpointConfig.MessageHandler mh = new WebSocketEndpointConfig.MessageHandler();
                mh.setPattern((String) mhRaw.get("pattern"));
                mh.setResponse((String) mhRaw.get("response"));
                mhList.add(mh);
                for (String k : mhRaw.keySet()) {
                    if (!KNOWN_MH_KEYS.contains(k)) {
                        log.warn("WebSocket '{}' 的 messageHandler 中包含未知配置项 '{}'，已忽略", ws.getId(), k);
                    }
                }
            }
            ws.setMessageHandlers(mhList);
        }

        Map<String, Object> hbRaw = (Map<String, Object>) wsRaw.get("heartbeat");
        if (hbRaw != null) {
            WebSocketEndpointConfig.HeartbeatConfig hb = new WebSocketEndpointConfig.HeartbeatConfig();
            if (hbRaw.containsKey("interval")) {
                hb.setInterval(((Number) hbRaw.get("interval")).longValue());
            }
            hb.setMessage((String) hbRaw.get("message"));
            ws.setHeartbeat(hb);
            for (String k : hbRaw.keySet()) {
                if (!KNOWN_HB_KEYS.contains(k)) {
                    log.warn("WebSocket '{}' 的 heartbeat 中包含未知配置项 '{}'，已忽略", ws.getId(), k);
                }
            }
        }

        for (String key : wsRaw.keySet()) {
            if (!KNOWN_WS_KEYS.contains(key)) {
                log.warn("WebSocket '{}' 中包含未知配置项 '{}'，可能属于拼写错误，已忽略", ws.getId(), key);
            }
        }
        return ws;
    }
}
