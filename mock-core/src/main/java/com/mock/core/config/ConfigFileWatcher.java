package com.mock.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件监听器：监视 mock-endpoints.yml 文件变更，自动触发配置热加载。
 *
 * 路径解析优先级：
 * 1. 显式配置 mock.watch-path（如 file:./config/mock-endpoints.yml）
 * 2. 自动探测 classpath:mock-endpoints.yml 的文件系统绝对路径（IDE/源码运行时有效）
 * 3. 文件在 JAR 包内时，热更新不可用，仅打印说明
 */
@Component
public class ConfigFileWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConfigFileWatcher.class);
    // Fix-2: SafeConstructor 禁止 !! 标签实例化任意 Java 类，防止 YAML 注入
    private static final org.yaml.snakeyaml.Yaml YAML =
        new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor());

    private final ReloadableConfigHolder holder;
    private final String watchPath;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mock-config-watcher");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastReloadTime = 0;
    private static final long DEBOUNCE_MS = 500;
    private static final int MAX_RETRIES = 10;
    private static final long MAX_BACKOFF_MS = 60_000;

    public ConfigFileWatcher(ReloadableConfigHolder holder,
                              @Value("${mock.watch-path:}") String watchPath) {
        this.holder = holder;
        this.watchPath = watchPath;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWatching() {
        String fsPath = resolveWatchPath();
        if (fsPath == null) {
            return;
        }

        File file = new File(fsPath);
        if (!file.exists()) {
            log.warn("文件监听路径不存在: {}", file.getAbsolutePath());
            return;
        }

        File parentFile = file.getParentFile();
        final Path watchedDir = parentFile != null
            ? parentFile.toPath()
            : Paths.get(".").toAbsolutePath();

        running.set(true);
        executor.submit(() -> watchLoop(file, watchedDir));
    }

    /**
     * 解析要监听的文件系统路径：
     * 1. 显式配置 mock.watch-path 时直接使用
     * 2. 否则尝试将 classpath:mock-endpoints.yml 解析为文件系统路径（IDE/源码模式下可行）
     * 3. 文件在 JAR 包内时（getFile() 抛 IOException），热更新不可用，返回 null
     */
    private String resolveWatchPath() {
        if (watchPath != null && !watchPath.isEmpty()) {
            return watchPath.startsWith("file:") ? watchPath.substring(5) : watchPath;
        }
        try {
            ClassPathResource resource = new ClassPathResource("mock-endpoints.yml");
            if (resource.exists()) {
                File file = resource.getFile(); // JAR 内时抛 IOException
                log.info("自动检测到配置文件: {}，启用文件热更新监听", file.getAbsolutePath());
                return file.getAbsolutePath();
            }
        } catch (IOException ignored) {
            log.info("mock-endpoints.yml 已从 classpath 加载（文件位于 JAR 包内），文件系统热更新不可用。" +
                "如需热更新，将配置文件放到 JAR 外部并启动时添加: --mock.watch-path=file:/路径/mock-endpoints.yml");
        }
        return null;
    }

    private void watchLoop(File file, Path watchedDir) {
        int retries = 0;
        while (running.get() && retries < MAX_RETRIES) {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                watchedDir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                retries = 0; // 成功建立监听后重置重试计数
                log.info("文件监听已启动: {} (目录: {})", file.getAbsolutePath(), watchedDir);
                while (running.get()) {
                    WatchKey key;
                    try {
                        key = watchService.poll(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (key == null) {
                        continue;
                    }
                    for (WatchEvent<?> event : key.pollEvents()) {
                        // Fix-1: OVERFLOW 事件 context() 不是 Path，跳过否则 ClassCastException 会崩溃监听线程
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }
                        Path changed = watchedDir.resolve((Path) event.context());
                        if (changed.toFile().getAbsolutePath().equals(file.getAbsolutePath())) {
                            long now = System.currentTimeMillis();
                            if (now - lastReloadTime > DEBOUNCE_MS) {
                                lastReloadTime = now;
                                log.info("检测到配置文件变更: {}，开始热加载...", changed);
                                reloadFromFile(file);
                            }
                        }
                    }
                    if (!key.reset()) {
                        log.warn("WatchKey 已失效，文件监听停止");
                        break;
                    }
                }
            } catch (Exception e) {
                log.error("文件监听异常: {}", e.getMessage(), e);
            }
            if (!running.get()) {
                break;
            }
            retries++;
            if (retries < MAX_RETRIES) {
                long delay = Math.min(1000L << retries, MAX_BACKOFF_MS);
                log.info("文件监听将在 {}ms 后重试 (第 {}/{} 次)", delay, retries, MAX_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else {
                log.error("文件监听已达最大重试次数 ({}), 停止监听", MAX_RETRIES);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void reloadFromFile(File file) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> root = (Map<String, Object>) YAML.load(content);

            MockConfigProperties newConfig = YamlConfigParser.parse(root);

            MockConfigProperties old;
            // Fix-5: synchronized 仅防止两个并发 reload 操作交错覆盖；
            // 路由匹配路径通过 AtomicReference 直接读取，无需持有此锁
            synchronized (holder) {
                old = holder.get();
                holder.set(newConfig);
            }

            log.info("热加载完成: {} 个 endpoint, {} 个 websocket (之前: {} 个 endpoint)",
                newConfig.getEndpoints().size(), newConfig.getWebsockets().size(),
                old != null ? old.getEndpoints().size() : 0);
        } catch (Exception e) {
            log.error("热加载失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdown();
    }
}
