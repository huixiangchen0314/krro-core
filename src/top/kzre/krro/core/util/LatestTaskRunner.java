package top.kzre.krro.core.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 最新任务执行器——按 key 路由、合并等待提交、取代被顶替的 future。
 *
 * <h2>批次语义</h2>
 * <p>每个 key 有 {@code current}（运行中）和 {@code pending}（等待中）：
 * <ul>
 *   <li>空闲提交——创建 current——立即启动——返回该批次 future</li>
 *   <li>运行中提交——创建 pending——返回其 future</li>
 *   <li>运行中提交（已有 pending）——参数 merge 到 pending——
 *       <b>旧 pending.future 被 reject</b>——新 future 取代</li>
 * </ul>
 *
 * <h2>Future 归属</h2>
 * <ul>
 *   <li>current 的 future——正常完成/异常完成——其提交者确实被执行</li>
 *   <li>pending 被取代——旧 future reject（{@link SupersededException}）
 *       ——提交者知道自己的提交被更新的取代</li>
 *   <li>pending 最终执行——最新 future 完成——之前的 reject</li>
 * </ul>
 *
 * <p><b>「最新」</b>的双重含义：
 * <ul>
 *   <li>参数层面——多个等待提交的 params 通过 {@link Mergable#merge}
 *       合并——典型策略是保留最新</li>
 *   <li>Future 层面——每个提交独立 future——被取代的 reject——
 *       最终一个完成</li>
 * </ul>
 */
public class LatestTaskRunner<P extends Mergable<P>>
        extends CoalescedTaskRunner<P>
        implements AutoCloseable {

    private final AsyncExecutor executor;
    private final ConcurrentHashMap<Object, KeyState> states = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LatestTaskRunner(CoalescedTask<P> task, AsyncExecutor executor) {
        super(task);
        if (executor == null) {
            throw new NullPointerException("executor");
        }
        this.executor = executor;
    }

    /**
     * 创建一个拥有专用 executor 的 LatestTaskRunner——
     * {@link #close()} 时同时关闭 executor。
     */

    public static <P extends Mergable<P>> LatestTaskRunner<P> withOwnedExecutor(
            CoalescedTask<P> task,
            Supplier<AsyncExecutor> executorFactory) {
        AsyncExecutor exec = executorFactory.get();
        return new LatestTaskRunner(task, exec) {
            @Override
            public void close() {
                try {
                    super.close();
                } finally {
                    closeQuietly(exec);
                }
            }
        };
    }

    private static void closeQuietly(AsyncExecutor exec) {
        if (exec instanceof AutoCloseable) {
            try {
                ((AutoCloseable) exec).close();
            } catch (Throwable t) {
                System.err.println("[LatestTaskRunner] close executor failed: " + t);
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 提交
    // ═══════════════════════════════════════════════

    @Override
    public CompletableFuture<Void> submit(Object key, P params) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (params == null) {
            throw new NullPointerException("params");
        }

        if (closed.get()) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(
                    new IllegalStateException("LatestTaskRunner has been closed"));
            return f;
        }

        KeyState state = states.computeIfAbsent(key, k -> new KeyState());
        return state.submit(params);
    }

    // ═══════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (KeyState state : states.values()) {
            state.cancel();
        }
        states.clear();
    }

    // ═══════════════════════════════════════════════
    // 异常
    // ═══════════════════════════════════════════════

    /**
     * 提交被取代时，其 future 以此异常 reject。
     */
    public static final class SupersededException extends RuntimeException {
        public SupersededException() {
            super("submission superseded by a newer one");
        }
    }

    // ═══════════════════════════════════════════════
    // 内部——每 key 状态
    // ═══════════════════════════════════════════════

    private class KeyState {

        private Batch current;
        private Batch pending;

        CompletableFuture<Void> submit(P params) {
            Batch toStart = null;
            CompletableFuture<Void> toReject = null;
            CompletableFuture<Void> result;

            synchronized (this) {
                if (current == null) {
                    // 空闲——创建 current——立即启动
                    current = new Batch(params);
                    toStart = current;
                    result = current.future;
                } else if (pending == null) {
                    // 运行中——创建 pending
                    pending = new Batch(params);
                    result = pending.future;
                } else {
                    // 运行中——已有 pending——合并参数——取代 future
                    CompletableFuture<Void> newFuture = new CompletableFuture<>();
                    toReject = pending.future;
                    pending.params = pending.params.merge(params);
                    pending.future = newFuture;
                    result = newFuture;
                }
            }

            // 锁外——reject 旧 future——启动新批次
            if (toReject != null) {
                toReject.completeExceptionally(new SupersededException());
            }
            if (toStart != null) {
                startBatch(toStart);
            }
            return result;
        }

        private void startBatch(Batch batch) {
            CompletableFuture<Void> execFuture;
            try {
                execFuture = executor.submit(() -> {
                    task.run(batch.params);
                    return null;
                });
            } catch (Throwable t) {
                onBatchDone(batch, t);
                return;
            }
            execFuture.whenComplete((v, e) -> onBatchDone(batch, e));
        }

        private void onBatchDone(Batch batch, Throwable error) {
            Batch toStart = null;
            CompletableFuture<Void> toComplete = null;
            boolean completeExceptionally = false;

            synchronized (this) {
                if (error != null) {
                    // 失败——完成 current——清 pending（也 reject）
                    toComplete = batch.future;
                    completeExceptionally = true;
                    current = null;
                    if (pending != null) {
                        pending.future.completeExceptionally(error);
                        pending = null;
                    }
                } else if (pending != null) {
                    // 成功——current 完成——pending 提升
                    toComplete = batch.future;
                    current = pending;
                    pending = null;
                    toStart = current;
                } else {
                    // 成功——无等待——空闲
                    toComplete = batch.future;
                    current = null;
                }
            }

            if (toComplete != null) {
                if (completeExceptionally) {
                    toComplete.completeExceptionally(error);
                } else {
                    toComplete.complete(null);
                }
            }
            if (toStart != null) {
                startBatch(toStart);
            }
        }

        synchronized void cancel() {
            if (current != null && !current.future.isDone()) {
                current.future.cancel(false);
            }
            if (pending != null && !pending.future.isDone()) {
                pending.future.cancel(false);
            }
            current = null;
            pending = null;
        }
    }

    private class Batch {
        P params;
        CompletableFuture<Void> future = new CompletableFuture<>();

        Batch(P params) {
            this.params = params;
        }
    }
}