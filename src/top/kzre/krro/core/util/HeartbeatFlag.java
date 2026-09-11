package top.kzre.krro.core.util;

import clojure.lang.IDeref;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 心跳标志：多组件注册、自动超时恢复。
 * <p>
 * 使用 {@link System#nanoTime()} 计时，不受系统时钟回拨影响。
 * 线程安全，状态为不可变快照。
 * <p>
 * 语义：从未 beat 或已超时 → {@code false}；存在活跃心跳 → {@code true}。
 */
public final class HeartbeatFlag implements IDeref {

    private static final long DEFAULT_TIMEOUT_NANOS = 3_000_000_000L; // 3s

    private final long timeoutNanos;
    private final AtomicReference<State> state;

    private static final class State {
        static final State EMPTY = new State(null, Long.MIN_VALUE);

        final Object key;
        final long timestampNanos;

        State(Object key, long timestampNanos) {
            this.key = key;
            this.timestampNanos = timestampNanos;
        }

        boolean isExpired(long nowNanos, long timeoutNanos) {
            return nowNanos - timestampNanos > timeoutNanos;
        }
    }

    public HeartbeatFlag() {
        this(DEFAULT_TIMEOUT_NANOS);
    }

    public HeartbeatFlag(long timeoutNanos) {
        if (timeoutNanos <= 0) {
            throw new IllegalArgumentException("timeoutNanos must be > 0");
        }
        this.timeoutNanos = timeoutNanos;
        this.state = new AtomicReference<>(State.EMPTY);
    }

    /** 心跳更新：记录 key 与当前时刻。key 不能为 null。 */
    public void beat(Object key) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        state.set(new State(key, System.nanoTime()));
    }

    /** 清除心跳：仅当当前 key 与传入 key 相等时清除。 */
    public void clear(Object key) {
        if (key == null) return;
        state.updateAndGet(current -> key.equals(current.key) ? State.EMPTY : current);
    }

    /** 存在活跃且未过期的心跳时返回 true，否则 false。 */
    public boolean get() {
        State s = state.get();
        if (s.key == null) return false;
        return !s.isExpired(System.nanoTime(), timeoutNanos);
    }

    /** 返回当前活跃的 key（未超时），否则 null。 */
    public Object getActiveKey() {
        State s = state.get();
        if (s.key == null) return null;
        return s.isExpired(System.nanoTime(), timeoutNanos) ? null : s.key;
    }

    @Override
    public Object deref() {
        return get();
    }
}