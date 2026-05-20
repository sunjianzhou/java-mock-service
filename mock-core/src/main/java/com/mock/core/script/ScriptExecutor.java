package com.mock.core.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mock.core.util.JsonEscape;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.Map;

/**
 * Nashorn JavaScript 脚本执行器（Java 8 内置，零依赖）。
 *
 * 警告：Nashorn 引擎自 JDK 11 起标记为 deprecated，JDK 15 起已移除。
 * 当前项目锁定 Java 8 编译运行。如需升级 JVM 版本，请评估替换为
 * GraalVM JavaScript 引擎或迁移 Groovy。
 *
 * 使用 ThreadLocal 为每个线程维护独立的 ScriptEngine 实例，
 * 避免并发 put()/eval() 导致的参数串扰。
 */
public class ScriptExecutor {

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
