package com.mock.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock 服务自定义 Prometheus 指标。
 * 在生产环境自动注入 Actuator 的 MeterRegistry，测试环境回退到 globalRegistry。
 */
@Component
public class MockMetrics {

    private static final int MAX_LOG = 200;

    private MeterRegistry registry = Metrics.globalRegistry;
    private final AtomicInteger wsSessionCount = new AtomicInteger(0);
    private final AtomicInteger recordingGauge = new AtomicInteger(0);
    private final AtomicInteger replayGauge = new AtomicInteger(0);

    /** 每个端点的请求统计：endpointId → [total, success(<400), error(>=400)] */
    private final ConcurrentHashMap<String, long[]> endpointStats = new ConcurrentHashMap<>();
    /** 最近请求日志（最多 MAX_LOG 条，最新在前） */
    private final Deque<RequestLogEntry> requestLog = new ArrayDeque<>();

    private final java.util.concurrent.ConcurrentHashMap<String, Counter> counterCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Timer> timerCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean gaugesRegistered = false;

    public MockMetrics() {}

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry registry) {
        this.registry = registry;
        ensureGauges();
    }

    private void ensureGauges() {
        if (!gaugesRegistered) {
            synchronized (this) {
                if (!gaugesRegistered) {
                    registry.gauge("mock_websocket_sessions", wsSessionCount);
                    registry.gauge("mock_recording_active", recordingGauge);
                    registry.gauge("mock_replay_active", replayGauge);
                    gaugesRegistered = true;
                }
            }
        }
    }

    public void recordRequest(String method, String endpointId, int status, long durationMs) {
        ensureGauges();
        String ep = endpointId != null ? endpointId : "unknown";
        String counterKey = method + "|" + ep + "|" + status;
        Counter counter = counterCache.computeIfAbsent(counterKey, k ->
            Counter.builder("mock_requests_total")
                .description("Total mock requests")
                .tag("method", method)
                .tag("endpoint", ep)
                .tag("status", String.valueOf(status))
                .register(registry));
        counter.increment();

        String timerKey = method + "|" + ep;
        Timer timer = timerCache.computeIfAbsent(timerKey, k ->
            Timer.builder("mock_request_duration_seconds")
                .description("Mock request duration")
                .tag("method", method)
                .tag("endpoint", ep)
                .register(registry));
        timer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);

        // 更新端点统计计数
        endpointStats.compute(ep, (k, v) -> {
            if (v == null) v = new long[3];
            v[0]++;                         // total
            if (status < 400) v[1]++;       // success
            else               v[2]++;       // error
            return v;
        });
    }

    /**
     * 记录一条请求日志（由 MockRequestHandler 在请求完成时调用）。
     * 超过 MAX_LOG 条时淘汰最旧的记录。
     */
    public synchronized void addRequestLog(RequestLogEntry entry) {
        while (requestLog.size() >= MAX_LOG) requestLog.pollFirst();
        requestLog.addLast(entry);
    }

    /** 返回最近请求列表（最新在前），副本，线程安全。 */
    public synchronized List<RequestLogEntry> getRecentRequests() {
        List<RequestLogEntry> list = new ArrayList<>(requestLog);
        Collections.reverse(list);
        return list;
    }

    /** 返回每个端点的统计：{endpointId → {total, success, error}}。 */
    public Map<String, Map<String, Long>> getEndpointStats() {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        endpointStats.forEach((ep, counts) -> {
            Map<String, Long> m = new LinkedHashMap<>();
            m.put("total",   counts[0]);
            m.put("success", counts[1]);
            m.put("error",   counts[2]);
            result.put(ep, m);
        });
        return result;
    }

    public void setRecordingActive(boolean active) {
        recordingGauge.set(active ? 1 : 0);
    }

    public void setReplayActive(boolean active) {
        replayGauge.set(active ? 1 : 0);
    }

    public void incrementWsSessions() {
        wsSessionCount.incrementAndGet();
    }

    public void decrementWsSessions() {
        wsSessionCount.decrementAndGet();
    }
}
