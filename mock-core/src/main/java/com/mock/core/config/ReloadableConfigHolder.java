package com.mock.core.config;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 可热加载的配置持有者，允许运行时更新 MockConfigProperties。
 */
@Component
public class ReloadableConfigHolder {

    private final AtomicReference<MockConfigProperties> configRef = new AtomicReference<>();
    private volatile Runnable onReload;

    public void setOnReload(Runnable callback) {
        this.onReload = callback;
    }

    public void set(MockConfigProperties config) {
        configRef.set(config);
        Runnable cb = onReload;
        if (cb != null) {
            cb.run();
        }
    }

    public MockConfigProperties get() {
        return configRef.get();
    }
}
