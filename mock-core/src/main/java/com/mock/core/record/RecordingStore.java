package com.mock.core.record;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 录制存储：管理录制记录的增删查和文件持久化。
 * 使用 LinkedList + synchronized 替代 CopyOnWriteArrayList：
 *   - add() O(1) 无数组拷贝
 *   - 内置容量上限 10000，超出时淘汰最早记录
 */
@Component
public class RecordingStore {

    private static final Logger log = LoggerFactory.getLogger(RecordingStore.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RECORDINGS = 10000;

    private String storageDir = "recordings";
    private final List<RecordedExchange> recordings = new LinkedList<>();
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean replaying = new AtomicBoolean(false);

    public boolean isRecording() { return recording.get(); }
    public boolean isReplaying() { return replaying.get(); }

    public void startRecording() {
        recording.set(true);
        log.info("Recording started");
    }

    public void stopRecording() {
        recording.set(false);
        log.info("Recording stopped ({} records)", recordings.size());
    }

    public void startReplay() {
        replaying.set(true);
        log.info("Replay mode enabled ({} records)", recordings.size());
    }

    public void stopReplay() {
        replaying.set(false);
        log.info("Replay mode disabled");
    }

    public void setStorageDir(String dir) {
        this.storageDir = dir != null && !dir.trim().isEmpty() ? dir : "recordings";
    }

    public void add(RecordedExchange record) {
        synchronized (recordings) {
            while (recordings.size() >= MAX_RECORDINGS) {
                recordings.remove(0);
            }
            recordings.add(record);
        }
    }

    public List<RecordedExchange> list() {
        synchronized (recordings) {
            return new ArrayList<>(recordings);
        }
    }

    public RecordedExchange get(String id) {
        synchronized (recordings) {
            return recordings.stream()
                .filter(r -> r.getId().equals(id))
                .findFirst()
                .orElse(null);
        }
    }

    public RecordedExchange findMatch(String method, String path) {
        synchronized (recordings) {
            return recordings.stream()
                .filter(r -> r.getMethod().equalsIgnoreCase(method)
                    && r.getPath().equals(path))
                .findFirst()
                .orElse(null);
        }
    }

    /** 按 endpointId 匹配（支持 pathPattern 动态路由回放）。 */
    public RecordedExchange findMatchByEndpoint(String method, String endpointId) {
        synchronized (recordings) {
            return recordings.stream()
                .filter(r -> r.getMethod().equalsIgnoreCase(method)
                    && endpointId.equals(r.getEndpointId()))
                .findFirst()
                .orElse(null);
        }
    }

    public void clear() {
        synchronized (recordings) {
            recordings.clear();
        }
        log.info("Recordings cleared");
    }

    public int size() {
        synchronized (recordings) {
            return recordings.size();
        }
    }

    // ---- 文件持久化 ----

    public void saveToFile() {
        File dir = new File(storageDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File file = new File(dir, "recordings.json");
        // I/O in synchronized — 先快照，释放锁后再序列化+写盘
        List<RecordedExchange> snapshot;
        synchronized (recordings) {
            snapshot = new ArrayList<>(recordings);
        }
        try {
            String json = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(snapshot);
            Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
            log.info("Saved {} recordings to {}", snapshot.size(), file.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to save recordings: {}", e.getMessage());
        }
    }

    public int loadFromFile() {
        File file = new File(storageDir, "recordings.json");
        if (!file.exists()) {
            return 0;
        }
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            List<RecordedExchange> loaded = mapper.readValue(data,
                new TypeReference<List<RecordedExchange>>() {});
            synchronized (recordings) {
                recordings.clear();
                recordings.addAll(loaded);
            }
            log.info("Loaded {} recordings from {}", loaded.size(), file.getAbsolutePath());
            return loaded.size();
        } catch (IOException e) {
            log.error("Failed to load recordings: {}", e.getMessage());
            return 0;
        }
    }
}
