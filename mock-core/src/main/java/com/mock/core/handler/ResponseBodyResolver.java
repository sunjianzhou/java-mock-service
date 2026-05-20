package com.mock.core.handler;

import com.mock.core.config.EndpointConfig;
import com.mock.core.script.ScriptExecutor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * 响应体解析器：按优先级链解析响应内容（异步，避免阻塞事件循环）。
 * 优先级: responseScript > responseBodyFile > conditionalResponse > responseBody
 */
public class ResponseBodyResolver {

    private final ScriptExecutor scriptExecutor;

    public ResponseBodyResolver(ScriptExecutor scriptExecutor) {
        this.scriptExecutor = scriptExecutor;
    }

    /**
     * 按优先级解析响应体 (异步):
     *   1) responseScript（JS 脚本）— 最高，在 boundedElastic 执行
     *   2) responseBodyFile（外部文件）— 其次，在 boundedElastic 读取
     *   3) conditionalResponse（条件分支）— 再次，同步
     *   4) responseBody（静态文本）— 兜底，同步
     */
    public Mono<String> resolve(EndpointConfig config, Map<String, String> params) {
        // 1) 脚本 — 可能在事件循环阻塞，切到 boundedElastic
        if (notBlank(config.getResponseScript())) {
            return Mono.fromCallable(() ->
                scriptExecutor.execute(config.getResponseScript(), params))
                .subscribeOn(Schedulers.boundedElastic());
        }
        // 2) 外部文件 — 阻塞 I/O，切到 boundedElastic
        if (notBlank(config.getResponseBodyFile())) {
            String filePath = config.getResponseBodyFile();
            return Mono.fromCallable(() ->
                com.mock.core.util.ResourceReader.readFile(filePath))
                .subscribeOn(Schedulers.boundedElastic())
                .map(fileContent -> {
                    if (fileContent == null) {
                        return "{\"error\":\"Failed to read responseBodyFile: " + filePath + "\"}";
                    }
                    return fileContent;
                });
        }
        // 3) 条件响应 — 纯内存操作
        EndpointConfig.ConditionalResponse cond = config.getConditionalResponse();
        if (cond != null && cond.getParam() != null) {
            String paramValue = params.getOrDefault(cond.getParam(), "");
            String matchedBody = cond.getCases().get(paramValue);
            if (matchedBody != null) {
                return Mono.just(matchedBody);
            }
            if (cond.getDefaultResponse() != null) {
                return Mono.just(cond.getDefaultResponse());
            }
        }
        // 4) 静态响应体
        return Mono.justOrEmpty(config.getResponseBody());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }
}

