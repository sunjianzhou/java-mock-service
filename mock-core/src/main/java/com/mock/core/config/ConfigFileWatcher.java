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
 * 仅在 watch-path 属性配置为文件系统路径时启用（如 file:./config/mock-endpoints.yml）。
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
        if (watchPath == null || watchPath.isEmpty()) {
            log.info("mock.watch-path 未配置，文件监听未启用");
            return;
        }

        String fsPath = watchPath;
        if (fsPath.startsWith("file:")) {
            fsPath = fsPath.substring(5);
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
