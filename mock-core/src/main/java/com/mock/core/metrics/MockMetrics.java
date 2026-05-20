package com.mock.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock 服务自定义 Prometheus 指标。
 * 在生产环境自动注入 Actuator 的 MeterRegistry，测试环境回退到 globalRegistry。
 */
@Component
public class MockMetrics {

    private MeterRegistry registry = Metrics.globalRegistry;
    private final AtomicInteger wsSessionCount = new AtomicInteger(0);
    private final AtomicInteger recordingGauge = new AtomicInteger(0);
    private final AtomicInteger replayGauge = new AtomicInteger(0);

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
