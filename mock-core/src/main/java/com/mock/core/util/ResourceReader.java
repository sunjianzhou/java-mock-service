package com.mock.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 统一资源读取器：支持 classpath: 和 file: 前缀，供多处复用。
 */
public final class ResourceReader {

    private static final Logger log = LoggerFactory.getLogger(ResourceReader.class);

    private ResourceReader() {}

    /**
     * 读取文件内容为 UTF-8 字符串。支持前缀：
     * <ul>
     *   <li>{@code classpath:path/to/file} — 从 classpath 读取</li>
     *   <li>{@code file:/absolute/path} — 从文件系统读取</li>
     *   <li>无前缀 — 先尝试 classpath，再尝试文件系统</li>
     * </ul>
     * @param path 资源路径
     * @return 文件内容，读取失败返回 null
     */
    public static String readFile(String path) {
        try {
            if (path.startsWith("classpath:")) {
                Resource resource = new ClassPathResource(path.substring(10));
                if (!resource.exists()) {
                    log.error("Classpath resource not found: {}", path);
                    return null;
                }
                return new String(Files.readAllBytes(Paths.get(resource.getURI())), StandardCharsets.UTF_8);
            }
            if (path.startsWith("file:")) {
                return new String(Files.readAllBytes(Paths.get(path.substring(5))), StandardCharsets.UTF_8);
            }
            // 默认先 classpath 后文件系统
            Resource resource = new ClassPathResource(path);
            if (resource.exists()) {
                return new String(Files.readAllBytes(Paths.get(resource.getURI())), StandardCharsets.UTF_8);
            }
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to read file: {} — {}", path, e.getMessage());
            return null;
        }
    }
}
