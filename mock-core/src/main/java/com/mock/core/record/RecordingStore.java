package com.mock.core.record;

import java.util.List;

/**
 * 录制存储抽象接口。默认实现：{@link InMemoryRecordingStore}（内存 + 本地文件）。
 * 如需跨实例共享录制数据，可实现此接口并注册为 Spring Bean（如 RedisRecordingStore）。
 */
public interface RecordingStore {
    boolean isRecording();
    boolean isReplaying();
    void startRecording();
    void stopRecording();
    void startReplay();
    void stopReplay();
    void setStorageDir(String dir);
    void add(RecordedExchange record);
    List<RecordedExchange> list();
    RecordedExchange get(String id);
    RecordedExchange findMatch(String method, String path);
    RecordedExchange findMatchByEndpoint(String method, String endpointId);
    void clear();
    int size();
    void saveToFile();
    int loadFromFile();
}
