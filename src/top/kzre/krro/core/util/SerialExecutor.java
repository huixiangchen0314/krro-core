package top.kzre.krro.core.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 串行执行器：把任意线程提交的任务串行化到单一线程上。
 *
 * <p>核心保证：
 * <ul>
 *   <li>所有任务在同一个 worker 线程上按提交顺序执行，无并发。</li>
 *   <li>可选 {@code onStart}：worker 线程启动时调用一次。</li>
 *   <li>可选 {@code onStop}：{@link #close()} 时在 worker 线程上调用一次。</li>
 *   <li>worker 线程是 daemon，懒启动（首次 submit 时才起）。</li>
 *   <li>队列<b>有界</b>，溢出策略可配置。</li>
 * </ul>
 *
 * <p>溢出策略（{@link OverflowPolicy}）：
 * <ul>
 *   <li>{@link OverflowPolicy#BLOCK}：阻塞提交线程，直到队列有空位。</li>
 *   <li>{@link OverflowPolicy#BLOCK_TIMEOUT}：阻塞最多 {@code timeoutMs} 毫秒，超时后返回 failed future。</li>
 *   <li>{@link OverflowPolicy#FAIL_FAST}：队列满时立即返回 failed future。</li>
 *   <li>{@link OverflowPolicy#DROP_OLDEST}：丢弃队列中最旧的任务（其 future 被取消），接受新任务。</li>
 * </ul>
 *
 * <p>背压建议：
 * <ul>
 *   <li>实时渲染 / 交互场景：{@code DROP_OLDEST}——旧帧已过时，直接丢弃。</li>
 *   <li>离线渲染 / 批处理：{@code BLOCK}——保证所有任务都被执行。</li>
 *   <li>服务端 / 防雪崩：{@code BLOCK_TIMEOUT} 或 {@code FAIL_FAST}——快速失败优于积压。</li>
 * </ul>
 *
 * <p>用法：
 * <pre>{@code
 * SerialExecutor exec = new SerialExecutor(
 *     "gl", 256, OverflowPolicy.BLOCK_TIMEOUT, 2000L);
 * exec.submit(() -> renderFrame()).thenApply(...);
 * exec.close();
 * }</pre>
 */
public final class SerialExecutor implements AutoCloseable {

    public enum OverflowPolicy {
        BLOCK,
        BLOCK_TIMEOUT,
        FAIL_FAST,
        DROP_OLDEST
    }

    private static final int  DEFAULT_CAPACITY   = 1024;
    private static final long DEFAULT_TIMEOUT_MS = 1000L;

    private static final class Slot {
        final Runnable             work;
        final CompletableFuture<?> future;
        Slot(Runnable work, CompletableFuture<?> future) {
            this.work   = work;
            this.future = future;
        }
    }

    private final BlockingQueue<Slot> queue;
    private final Thread              worker;
    private final AtomicBoolean       started = new AtomicBoolean(false);
    private final AtomicBoolean       closed  = new AtomicBoolean(false);
    private final Runnable            onStart;
    private final Runnable            onStop;
    private final OverflowPolicy      policy;
    private final long                timeoutMs;

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    public SerialExecutor(String threadNamePrefix,
                          int capacity,
                          OverflowPolicy policy,
                          long timeoutMs,
                          Runnable onStart,
                          Runnable onStop) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (policy == null) {
            throw new NullPointerException("policy");
        }
        if (policy == OverflowPolicy.BLOCK_TIMEOUT && timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be > 0 for BLOCK_TIMEOUT");
        }
        this.policy    = policy;
        this.timeoutMs = timeoutMs;
        this.onStart   = onStart;
        this.onStop    = onStop;
        this.queue     = new ArrayBlockingQueue<>(capacity);
        String prefix  = (threadNamePrefix == null || threadNamePrefix.isEmpty())
                ? "krro-serial" : threadNamePrefix;
        this.worker    = new Thread(this::runLoop, prefix + "-worker");
        this.worker.setDaemon(true);
    }

    public SerialExecutor(String threadNamePrefix, int capacity,
                          OverflowPolicy policy, long timeoutMs) {
        this(threadNamePrefix, capacity, policy, timeoutMs, null, null);
    }

    public SerialExecutor(String threadNamePrefix, int capacity, OverflowPolicy policy) {
        this(threadNamePrefix, capacity, policy, DEFAULT_TIMEOUT_MS, null, null);
    }

    public SerialExecutor(int capacity, OverflowPolicy policy) {
        this(null, capacity, policy, DEFAULT_TIMEOUT_MS, null, null);
    }

    public SerialExecutor() {
        this(null, DEFAULT_CAPACITY, OverflowPolicy.BLOCK, DEFAULT_TIMEOUT_MS, null, null);
    }

    // ═══════════════════════════════════════════════
    // 提交
    // ═══════════════════════════════════════════════

    /**
     * 提交一个有返回值的任务。任务在 worker 线程上执行。
     *
     * <p>返回的 future：
     * <ul>
     *   <li>成功：worker 执行完 task.call() 后以结果完成</li>
     *   <li>失败：任务抛异常 → completeExceptionally</li>
     *   <li>拒绝：队列满且策略不允许等待 → RejectedExecutionException</li>
     *   <li>丢弃：DROP_OLDEST 被淘汰时 → cancelled</li>
     * </ul>
     *
     * @throws IllegalStateException 若已 close
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        ensureOpen();

        CompletableFuture<T> future = new CompletableFuture<>();
        Slot slot = new Slot(() -> {
            if (future.isCancelled()) return;
            try {
                T result = task.call();
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, future);

        if (!enqueue(slot)) {
            future.completeExceptionally(
                    new RejectedExecutionException(
                            "SerialExecutor queue full (capacity=" + queue.size()
                                    + ", policy=" + policy + ")"));
        } else {
            startWorkerIfNeeded();
        }
        return future;
    }

    /**
     * 提交一个无返回值的任务。
     */
    public CompletableFuture<Void> submit(Runnable task) {
        return submit(() -> {
            task.run();
            return null;
        });
    }

    /**
     * 阻塞等待已提交任务全部完成。
     *
     * <p>实现：往队列尾部插入一个空任务并等它执行完。
     * 空任务完成时，之前的任务都已执行完毕。
     */
    public void awaitIdle() throws InterruptedException {
        ensureOpen();
        try {
            submit(() -> null).get();
        } catch (ExecutionException e) {
            throw new RuntimeException("awaitIdle failed", e.getCause());
        }
    }

    // ═══════════════════════════════════════════════
    // 背压核心
    // ═══════════════════════════════════════════════

    private boolean enqueue(Slot slot) {
        switch (policy) {
            case BLOCK:
                try {
                    queue.put(slot);   // 阻塞直到有空位
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            case BLOCK_TIMEOUT:
                try {
                    return queue.offer(slot, timeoutMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            case FAIL_FAST:
                return queue.offer(slot);   // 不阻塞，满则失败
            case DROP_OLDEST: {
                if (queue.offer(slot)) return true;   // 有空位，正常入队
                // 满了：丢弃队头，再重试
                Slot old = queue.poll();
                if (old != null) old.future.cancel(false);
                return queue.offer(slot);
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════

    private void startWorkerIfNeeded() {
        if (started.compareAndSet(false, true)) {
            worker.start();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("SerialExecutor has been closed");
        }
    }

    private void runLoop() {
        try {
            if (onStart != null) {
                try { onStart.run(); }
                catch (Throwable t) { t.printStackTrace(); }
            }
            while (!closed.get()) {
                Slot slot;
                try {
                    // 用 poll(timeout) 而非 take()，周期性检查 closed
                    slot = queue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (slot == null) continue;
                try {
                    slot.work.run();
                } catch (Throwable t) {
                    // slot.work 内部已 completeExceptionally，这里是保底
                    t.printStackTrace();
                }
            }
        } finally {
            if (onStop != null) {
                try { onStop.run(); }
                catch (Throwable t) { t.printStackTrace(); }
            }
            // 队列中残留的任务统一取消
            Slot s;
            while ((s = queue.poll()) != null) {
                s.future.cancel(false);
            }
        }
    }

    /**
     * 关闭执行器：中断 worker，等待其退出（最多 5 秒），
     * 队列中残留任务被取消。幂等。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (started.get()) {
            worker.interrupt();
            try {
                worker.join(5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 查询（诊断用）
    // ═══════════════════════════════════════════════

    /** 当前队列中待执行的任务数。 */
    public int queueSize() {
        return queue.size();
    }

    /** 队列剩余空位数（背压余量）。 */
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }

    public boolean isClosed() {
        return closed.get();
    }
}