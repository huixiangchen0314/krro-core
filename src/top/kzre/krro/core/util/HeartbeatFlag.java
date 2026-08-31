package top.kzre.krro.core.util;

import clojure.lang.IDeref;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 心跳标志，支持多组件注册，自动超时恢复。
 * 线程安全，使用不可变状态快照。
 */
public final class HeartbeatFlag implements IDeref {
    private static final long DEFAULT_TIMEOUT_MS = 3000;

    private final long timeoutMs;
    private final boolean defaultValue;
    private final AtomicReference<State> state;

    private static final class State {
        final Object key;
        final long timestamp;

        State(Object key, long timestamp) {
            this.key = key;
            this.timestamp = timestamp;
        }

        boolean isExpired(long now, long timeout) {
            return now - timestamp > timeout;
        }
    }

    public HeartbeatFlag(boolean defaultValue) {
        this(defaultValue, DEFAULT_TIMEOUT_MS);
    }

    public HeartbeatFlag(boolean defaultValue, long timeoutMs) {
        this.defaultValue = defaultValue;
        this.timeoutMs = timeoutMs;
        this.state = new AtomicReference<>(new State(null, 0));
    }

    /**
     * 心跳更新：设置当前 key 和时间戳。
     */
    public void beat(Object key) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        long now = System.currentTimeMillis();
        State newState = new State(key, now);
        // 直接替换，无需比较旧值（因为心跳总是更新为最新）
        state.set(newState);
    }

    /**
     * 清除心跳：仅当当前 key 与传入 key 匹配时才清除。
     */
    public void clear(Object key) {
        if (key == null) return;
        state.updateAndGet(current -> {
            if (key.equals(current.key)) {
                return new State(null, 0);
            }
            return current;
        });
    }

    /**
     * 检查是否活跃（心跳有效且未超时）。
     */
    public boolean get() {
        State s = state.get();
        if (s.key == null) return defaultValue;
        long now = System.currentTimeMillis();
        return s.isExpired(now, timeoutMs) == defaultValue;
    }

    /**
     * 返回当前活跃的 key（若未超时），否则返回 null。
     */
    public Object getActiveKey() {
        State s = state.get();
        if (s.key == null) return null;
        long now = System.currentTimeMillis();
        return s.isExpired(now, timeoutMs) ? null : s.key;
    }

    @Override
    public Object deref() {
        return get();
    }
}