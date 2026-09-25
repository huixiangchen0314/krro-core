package top.kzre.krro.core.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 线程安全的资源追踪器。
 *
 * <p><b>用途</b>：在可能失败且可能并发的场景中，追踪一组拥有所有权的
 * 资源，关闭时统一释放，并保证『关闭后新登记的资源』也能被立即释放，
 * 不存在『已释放 → 后续 track 塞入失效列表』的泄漏窗口。
 *
 * <p><b>语义</b>：
 * <ul>
 *   <li>{@link #track} —— 登记资源；若已关闭，则立即释放</li>
 *   <li>{@link #close} —— 标记关闭并释放所有已登记资源，幂等</li>
 *   <li>{@link #isClosed} —— 查询当前是否已进入关闭态</li>
 * </ul>
 *
 * <p><b>并发不变量</b>：{@code track} 与 {@code close} 通过 CAS 互斥，
 * 任一资源要么在列表里被 {@code close} 清，要么在 {@code track} 时读到
 * closed 已置位而立即自清。无泄漏窗口。
 *
 * <p><b>前提</b>：{@code releaseFn} 幂等、可重入（正常流可能已释放过的
 * 资源会被 {@code close} 再释放一次）。
 *
 * <p><b>线程契约</b>：任意线程可调 {@code track} / {@code close} /
 * {@code isClosed}。
 *
 * @param <T> 资源类型
 */
public final class Tracker<T> implements AutoCloseable{

    /** 不可变快照。CAS 比较引用身份。 */
    private static final class State<T> {
        final List<T> resources;
        final boolean closed;

        State(List<T> resources, boolean closed) {
            this.resources = resources;
            this.closed = closed;
        }
    }

    private final AtomicReference<State<T>> state;
    private final Consumer<T> releaseFn;

    /**
     * @param releaseFn 单个资源的释放动作。必须幂等、可重入。
     */
    public Tracker(Consumer<T> releaseFn) {
        if (releaseFn == null) {
            throw new IllegalArgumentException("releaseFn must not be null");
        }
        this.releaseFn = releaseFn;
        this.state = new AtomicReference<>(
                new State<>(new ArrayList<>(), false));
    }

    /**
     * 创建释放动作为 {@link AutoCloseable#close()} 的追踪器。
     *
     * <p>释放过程中的任何异常都被吞掉——{@code close()} 抛异常
     * 是常态（已关闭、资源无响应等），不应掩盖调用方的原始异常。
     */
    public static Tracker<AutoCloseable> autoCloseableTracker() {
        return new Tracker<>(c -> {
            try {
                c.close();
            } catch (Exception ignored) {
                // 释放失败不重抛，不掩盖调用方的原始异常
            }
        });
    }

    /**
     * 登记资源。
     *
     * <ul>
     *   <li>未关闭 —— 加入列表</li>
     *   <li>已关闭 —— 立即释放</li>
     * </ul>
     *
     * <p>{@code resource} 为 {@code null} 时不做任何事。
     *
     * @return resource 本身，便于链式调用
     */
    public T track(T resource) {
        if (resource == null) return null;
        while (true) {
            State<T> s = state.get();
            if (s.closed) {
                releaseFn.accept(resource);
                return resource;
            }
            List<T> next = new ArrayList<>(s.resources);
            next.add(resource);
            if (state.compareAndSet(s, new State<>(next, false))) {
                return resource;
            }
        }
    }

    /**
     * 标记关闭并释放所有已登记资源。幂等。
     *
     * <p>单个资源释放抛出的异常被吞掉，不掩盖调用方的原始异常。
     */
    @Override
    public void close() {
        while (true) {
            State<T> s = state.get();
            if (s.closed) return;
            if (state.compareAndSet(s,
                    new State<>(Collections.<T>emptyList(), true))) {
                for (T r : s.resources) {
                    try {
                        releaseFn.accept(r);
                    } catch (Throwable ignored) {
                        // 单个释放失败不影响其余资源
                    }
                }
                return;
            }
        }
    }

    /** 当前是否已进入关闭态。 */
    public boolean isClosed() {
        return state.get().closed;
    }
}