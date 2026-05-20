package com.mock.core.script;

import java.util.Map;

/**
 * 脚本执行引擎抽象接口，解耦具体 JS 引擎实现。
 * 默认实现：{@link ScriptExecutor}（Nashorn，Java 8）。
 * JDK 升级时可替换为 GraalVM JS / Groovy 实现，无需改动业务代码。
 */
public interface ScriptEngineExecutor {
    String execute(String script, Map<String, String> params);
}
