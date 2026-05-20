package com.mock.core.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mock.core.util.JsonEscape;

import org.springframework.stereotype.Component;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.Map;

/**
 * Nashorn JavaScript 脚本执行器（Java 8 内置，零依赖）。
 * 实现 {@link ScriptEngineExecutor}，JDK 升级时可替换为 GraalVM JS 实现。
 *
 * 使用 ThreadLocal 为每个线程维护独立的 ScriptEngine 实例，
 * 避免并发 put()/eval() 导致的参数串扰。
 * 注意：脚本中定义的全局变量会在同一线程的多次调用间累积，脚本应保持无状态。
 */
@Component
public class ScriptExecutor implements ScriptEngineExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScriptExecutor.class);

    private static final ThreadLocal<ScriptEngine> ENGINE_TL =
        ThreadLocal.withInitial(() -> new ScriptEngineManager().getEngineByName("nashorn"));

    public String execute(String script, Map<String, String> params) {
        ScriptEngine engine = ENGINE_TL.get();
        if (engine == null) {
            log.error("Nashorn engine not available");
            return "{\"error\":\"Script engine not available on this JVM\"}";
        }
        try {
            engine.put("params", params);
            Object result = engine.eval(script);
            return result != null ? result.toString() : "";
        } catch (ScriptException e) {
            log.error("Script execution failed: {}", e.getMessage(), e);
            return "{\"error\":\"Script error: " + JsonEscape.escape(e.getMessage()) + "\"}";
        } catch (Exception e) {
            log.error("Unexpected script error: {}", e.getMessage(), e);
            return "{\"error\":\"Script error: " + JsonEscape.escape(e.getMessage()) + "\"}";
        }
    }
}
