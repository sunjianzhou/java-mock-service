package com.mock.core.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置模板变量，HTTP 和 WebSocket 模板引擎共享。
 * 支持：{{now}} 当前时间、{{uuid}} 随机 UUID、{{seq}} 全局自增序列号。
 */
public final class BuiltinTemplateVars {

    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    public static final Pattern PATTERN =
        Pattern.compile("\\{\\{(now|uuid|seq)\\}\\}");

    private BuiltinTemplateVars() {}

    /** 替换模板字符串中所有内置变量。 */
    public static String apply(String template) {
        if (template == null || !template.contains("{{")) return template;
        Matcher m = PATTERN.matcher(template);
        if (!m.find()) return template;
        m.reset();
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(resolve(m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String resolve(String key) {
        switch (key) {
            case "now":  return LocalDateTime.now().format(DATETIME_FMT);
            case "uuid": return UUID.randomUUID().toString();
            case "seq":  return String.valueOf(SEQUENCE.incrementAndGet());
            default:     return "";
        }
    }
}
