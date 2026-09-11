package top.kzre.krro.core.util;

import clojure.lang.IDeref;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;


// ═══════════════════════════════════════════════
// 计算函数接口
// ═══════════════════════════════════════════════



/**
 * 信号原语。拉模型，惰性求值，脏标记传播。
 *
 * <h3>并发模型</h3>
 * <ul>
 *   <li>状态用 volatile 字段 + CAS 更新，无锁快速路径</li>
 *   <li>computeToken 保证异步结果不被过期计算覆盖</li>
 *   <li>lifecycle 三态（ATTACHED / DETACHED / DISPOSED）保证 attach/detach/dispose 正确</li>
 *   <li>后置检查：markDirty 遍历时清理失效依赖，修正 attach/detach/dispose 的并发交错</li>
 *   <li>writeResult 用 writeLock 串行化 token 检查 + 值写入，避免过期覆盖</li>
 * </ul>
 *
 * <h3>状态机</h3>
 * <pre>
 *   计算状态：DIRTY → COMPUTING → READY → DIRTY ...
 *   生命周期：ATTACHED ↔ DETACHED → DISPOSED（单向）
 * </pre>
 *
 * <h3>计算不丢弃</h3>
 * 计算完成时若已被标脏（status 从 COMPUTING 变 DIRTY），
 * 仍更新 value，但保持 DIRTY。下次 deref 触发新一轮计算。
 * 即保证 deref 当时的最新值，如果由于异步被更新了，不做处理，仅仅保持脏状态
 */
public final class Signal implements IDeref {
    @FunctionalInterface
    public interface SyncComputeFn {
        Object compute(Object[] inputValues);
    }

    @FunctionalInterface
    public interface AsyncComputeFn {
        /**
         * @param inputValues 依赖的当前值数组
         * @param submit      计算完成后调用，提交结果
         */
        void compute(Object[] inputValues, Consumer<Object> submit);
    }

    private static final int DIRTY     = 0;
    private static final int COMPUTING = 1;
    private static final int READY     = 2;
    private static final int ATTACHED  = 0;
    private static final int DETACHED  = 1;
    private static final int DISPOSED  = 2;

    private final SyncComputeFn  syncFn;
    private final AsyncComputeFn asyncFn;
    private final Signal[]       inputs;

    // 全部在 writeLock 内修改
    private volatile Object value; // 锁内写，锁外读，保证可见
    private volatile int    status    = DIRTY;
    private volatile int    lifecycle = ATTACHED;
    private long            computeToken = 0;   // 只在锁内读写，不需要 volatile

    private final Object writeLock = new Object();

    // 锁外修改（并发集合自身线程安全）
    private final Set<Signal>                         dependents = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Object, Runnable> watches    = new ConcurrentHashMap<>();

    private static final AtomicLong TOKEN_GEN = new AtomicLong(0);

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    private Signal(SyncComputeFn syncFn, AsyncComputeFn asyncFn, Signal[] inputs) {
        this.syncFn  = syncFn;
        this.asyncFn = asyncFn;
        this.inputs  = inputs == null ? new Signal[0] : inputs;
        for (Signal in : this.inputs) {
            in.dependents.add(this);
        }
    }

    public static Signal sync(SyncComputeFn f, Signal... inputs)  { return new Signal(f, null, inputs); }
    public static Signal async(AsyncComputeFn f, Signal... inputs) { return new Signal(null, f, inputs); }
    public static Signal source(SyncComputeFn getter)              { return new Signal(getter, null, new Signal[0]); }

    // ═══════════════════════════════════════════════
    // deref
    // ═══════════════════════════════════════════════

    @Override
    public Object deref() {
        // 快速路径：不需要锁
        // 读到 READY 或 DISPOSED 直接返回
        int s = status;                    // volatile 读
        if (s == READY || lifecycle == DISPOSED) {   // volatile 读
            return value;                  // volatile 读
        }
        return slowDeref();
    }

    private Object slowDeref() {
        long token;
        // 锁内：状态转换
        synchronized (writeLock) {
            if (lifecycle == DISPOSED) return value;
            if (status != DIRTY)       return value;   // 别人在算，或已 READY
            status = COMPUTING;
            token  = TOKEN_GEN.incrementAndGet();
            computeToken = token;
        }

        // 锁外：读输入 + 计算
        Object[] inputVals = readInputs();

        if (syncFn != null) {
            Object result = syncFn.compute(inputVals);
            writeResult(token, result);
            return result;
        } else {
            final long t = token;
            asyncFn.compute(inputVals, v -> writeResult(t, v));
            return value;   // 异步：立即返回旧值
        }
    }

    private Object[] readInputs() {
        Object[] vals = new Object[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            vals[i] = inputs[i].deref();
        }
        return vals;
    }

    // ═══════════════════════════════════════════════
    // writeResult
    // ═══════════════════════════════════════════════

    private void writeResult(long token, Object newVal) {
        Object oldVal;
        boolean changed;

        synchronized (writeLock) {
            if (computeToken != token) return;   // 被新计算顶替
            if (lifecycle == DISPOSED) return;
            oldVal  = value;
            value   = newVal;
            changed = !Objects.equals(newVal, oldVal);
            if (status == COMPUTING) {
                status = READY;      // 未被标脏：完成
            }
            // 若已被标脏（DIRTY）：保持 DIRTY，下次 deref 重算
        }

        if (changed) notifyDependentsOfChange();
    }

    private void notifyDependentsOfChange() {
        for (Runnable w : watches.values()) {
            try { w.run(); } catch (Throwable ignored) {}
        }
        for (Signal dep : dependents) {
            if (dep.lifecycle == ATTACHED) {
                dep.markDirty();
            } else {
                dependents.remove(dep);
            }
        }
    }

    // ═══════════════════════════════════════════════
    // markDirty
    // ═══════════════════════════════════════════════

    public void markDirty() {
        synchronized (writeLock) {
            if (lifecycle != ATTACHED) return;
            if (status == DIRTY)       return;   // 已经 dirty
            status = DIRTY;                      // 从 COMPUTING 或 READY 转 DIRTY
        }

        // 锁外通知 watches + 递归下游
        for (Runnable w : watches.values()) {
            try { w.run(); } catch (Throwable ignored) {}
        }
        for (Signal dep : dependents) {
            if (dep.lifecycle == ATTACHED) {
                dep.markDirty();
            } else {
                dependents.remove(dep);      // 后置清理
            }
        }
    }

    // ═══════════════════════════════════════════════
    // attach / detach / dispose
    // ═══════════════════════════════════════════════

    public void detach() {
        boolean changed = false;
        synchronized (writeLock) {
            if (lifecycle == ATTACHED) {
                lifecycle = DETACHED;
                changed = true;
            }
        }
        if (changed) {
            for (Signal in : inputs) in.dependents.remove(this);
        }
    }

    public void attach() {
        boolean changed = false;
        synchronized (writeLock) {
            if (lifecycle == DETACHED) {
                lifecycle = ATTACHED;
                changed = true;
            }
        }
        if (changed) {
            for (Signal in : inputs) in.dependents.add(this);
            // 检查输入状态：detach 期间上游可能已变化
            boolean inputsDirty = false;
            for (Signal in : inputs) {
                if (in.lifecycle == DISPOSED) continue;
                if (in.status != READY) { inputsDirty = true; break; }
            }
            if (inputsDirty) {
                synchronized (writeLock) {
                    if (status != DIRTY) status = DIRTY;
                }
            }
        }
    }

    public void dispose() {
        boolean changed = false;
        synchronized (writeLock) {
            if (lifecycle != DISPOSED) {
                lifecycle = DISPOSED;
                changed = true;
            }
        }
        if (changed) {
            for (Signal in : inputs) in.dependents.remove(this);
            watches.clear();
        }
    }

    // ═══════════════════════════════════════════════
    // watch
    // ═══════════════════════════════════════════════

    public Runnable watch(Object key, Runnable f) {
        watches.put(key, f);
        return () -> watches.remove(key);
    }

    public void unwatch(Object key) {
        watches.remove(key);
    }

    // ═══════════════════════════════════════════════
    // 状态查询（仅用于调试，业务侧不要依赖）
    // ═══════════════════════════════════════════════

    public boolean isReady()    { return status == READY; }
    public boolean isPending()  { return status == COMPUTING; }
    public boolean isDirty()    { return status == DIRTY; }
    public boolean isDisposed() { return lifecycle == DISPOSED; }
    public boolean isAttached() { return lifecycle == ATTACHED; }
    public boolean isDetached() { return lifecycle == DETACHED; }
    public Object  peekValue()  { return value; }
    public Signal[] getInputs() { return inputs; }
    public Set<Signal> getDependents() { return dependents; }
    public int watchCount()     { return watches.size(); }
}