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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存录制存储。
 * Fix-3: 用枚举状态机替换两个独立的 AtomicBoolean，确保 RECORDING 与 REPLAYING 互斥：
 *   - startRecording() 时若正在回放，自动停止回放并告警；
 *   - startReplay() 时若正在录制，自动停止录制并告警。
 */
@Component
public class InMemoryRecordingStore implements RecordingStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRecordingStore.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RECORDINGS = 10000;

    private enum Mode { IDLE, RECORDING, REPLAYING }

    private String storageDir = "recordings";
    private final Deque<RecordedExchange> recordings = new ArrayDeque<>();
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.IDLE);

    @Override public boolean isRecording() { return mode.get() == Mode.RECORDING; }
    @Override public boolean isReplaying() { return mode.get() == Mode.REPLAYING; }

    @Override
    public void startRecording() {
        Mode prev = mode.getAndSet(Mode.RECORDING);
        if (prev == Mode.REPLAYING) {
            log.warn("startRecording() called while replay was active — replay stopped automatically");
        }
        log.info("Recording started");
    }

    @Override
    public void stopRecording() {
        mode.compareAndSet(Mode.RECORDING, Mode.IDLE);
        log.info("Recording stopped ({} records)", size());
    }

    @Override
    public void startReplay() {
        Mode prev = mode.getAndSet(Mode.REPLAYING);
        if (prev == Mode.RECORDING) {
            log.warn("startReplay() called while recording was active — recording stopped automatically");
        }
        log.info("Replay mode enabled ({} records)", size());
    }

    @Override
    public void stopReplay() {
        mode.compareAndSet(Mode.REPLAYING, Mode.IDLE);
        log.info("Replay mode disabled");
    }

    @Override
    public void setStorageDir(String dir) {
        this.storageDir = dir != null && !dir.trim().isEmpty() ? dir : "recordings";
    }

    @Override
    public void add(RecordedExchange record) {
        synchronized (recordings) {
            while (recordings.size() >= MAX_RECORDINGS) {
                recordings.pollFirst();
            }
            recordings.addLast(record);
        }
    }

    @Override
    public List<RecordedExchange> list() {
        synchronized (recordings) { return new ArrayList<>(recordings); }
    }

    @Override
    public RecordedExchange get(String id) {
        synchronized (recordings) {
            return recordings.stream().filter(r -> r.getId().equals(id)).findFirst().orElse(null);
        }
    }

    @Override
    public RecordedExchange findMatch(String method, String path) {
        synchronized (recordings) {
            return recordings.stream()
                .filter(r -> r.getMethod().equalsIgnoreCase(method) && r.getPath().equals(path))
                .findFirst().orElse(null);
        }
    }

    @Override
    public RecordedExchange findMatchByEndpoint(String method, String endpointId) {
        synchronized (recordings) {
            return recordings.stream()
                .filter(r -> r.getMethod().equalsIgnoreCase(method)
                    && endpointId.equals(r.getEndpointId()))
                .findFirst().orElse(null);
        }
    }

    @Override
    public void clear() {
        synchronized (recordings) { recordings.clear(); }
        log.info("Recordings cleared");
    }

    @Override
    public int size() {
        synchronized (recordings) { return recordings.size(); }
    }

    @Override
    public void saveToFile() {
        File dir = new File(storageDir);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, "recordings.json");
        List<RecordedExchange> snapshot;
        synchronized (recordings) { snapshot = new ArrayList<>(recordings); }
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
            Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
            log.info("Saved {} recordings to {}", snapshot.size(), file.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to save recordings: {}", e.getMessage());
        }
    }

    @Override
    public int loadFromFile() {
        File file = new File(storageDir, "recordings.json");
        if (!file.exists()) return 0;
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
