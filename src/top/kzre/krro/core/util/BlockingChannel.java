package top.kzre.krro.core.util;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 线程安全的无缓冲阻塞 handoff 通道。
 *
 * <p>本实现采用单槽 handoff 模型：写入者只有在读取者就绪时才能发布元素。
 * {@code offer}/{@code get} 为阻塞操作，支持线程中断；{@code send}/{@code poll} 为非阻塞操作。
 *
 * <p><b>公平性：</b>本类采用公平配对策略。读取线程在 {@code notEmpty} 上等待，
 * 写入线程在 {@code notFull} 上等待。{@link Condition#signal()} 按 FIFO 顺序唤醒等待最久的线程，
 * 避免读者/写者饥饿以及唤醒信号丢失（lost wakeup）问题。
 *
 * <p><b>内存可见性：</b>所有状态变更（{@code filled}、{@code slot}、{@code closed}）
 * 均在同一个 {@link ReentrantLock} 保护下进行，通过锁的 happens-before 规则保证跨线程可见性，
 * 无需额外的 {@code volatile} 或原子变量。
 *
 * <p><b>元素丢弃回调：</b>当元素被丢弃（通道已关闭、无接收者时调用 {@code send}、
 * 或调用 {@link #clear()}）时，会通过构造时传入的 {@link Consumer} 回调精确通知一次。
 *
 * @param <T> 通道中传输元素的类型
 */
public class BlockingChannel<T> implements IChannel<T> {

    /** 保护所有可变状态的锁。 */
    private final ReentrantLock lock = new ReentrantLock();

    /** 当有值可读时被唤醒；读取线程在此等待。 */
    private final Condition notEmpty = lock.newCondition();

    /** 当槽位空闲时被唤醒；写入线程在此等待。 */
    private final Condition notFull = lock.newCondition();

    /** 单槽存储，仅在 filled == true 时持有有效值。 */
    private T slot = null;

    /** 槽位是否有值。 */
    private boolean filled = false;

    /** 元素丢弃时的回调。 */
    private final Consumer<T> onDispose;

    /** 是否已关闭。关闭后所有阻塞操作立即返回，写入操作被拒绝。 */
    private volatile boolean closed = false;

    /**
     * 构造一个无缓冲通道。
     *
     * @param onDispose 元素被丢弃时的回调；若为 null 则使用空回调
     */
    public BlockingChannel(Consumer<T> onDispose) {
        this.onDispose = onDispose != null ? onDispose : t -> {};
    }

    /**
     * 丢弃指定值（若非 null），通过 onDispose 回调通知。
     */
    private void dispose(T value) {
        if (value != null) {
            onDispose.accept(value);
        }
    }

    // ==================== 写入 ====================

    /**
     * 非阻塞写入。
     *
     * <p>若当前有等待中的读取者（槽位空闲），则立即将值放入槽位并唤醒一个读取线程，返回 true。
     * 若槽位已被占用（无读取者等待），则丢弃该值并返回 false。
     *
     * <p>通道关闭后调用此方法会丢弃值并返回 false。
     *
     * @param value 待写入的非 null 值
     * @return true 若值已成功 handoff 给读取者；false 若被丢弃
     * @throws NullPointerException 若 value 为 null
     */
    @Override
    public boolean send(T value) {
        if (value == null) {
            throw new NullPointerException("value cannot be null");
        }

        lock.lock();
        try {
            // 关闭状态下直接丢弃
            if (closed) {
                dispose(value);
                return false;
            }
            // 槽位空闲：放入值，唤醒等待最久的读取者
            if (!filled) {
                slot = value;
                filled = true;
                notEmpty.signal();
                return true;
            }
            // 槽位被占（无读取者就绪）：丢弃
            dispose(value);
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 阻塞写入。
     *
     * <p>若槽位空闲则立即放入并返回 true；否则阻塞等待，直到有读取者取走值腾出槽位
     * 或通道被关闭。
     *
     * <p>通道关闭后调用此方法会丢弃值并返回 false。
     *
     * @param value 待写入的非 null 值
     * @return true 若值已成功写入；false 若通道在写入前已关闭
     * @throws InterruptedException 若等待时被中断
     * @throws NullPointerException 若 value 为 null
     */
    @Override
    public boolean offer(T value) throws InterruptedException {
        if (value == null) {
            throw new NullPointerException("value cannot be null");
        }

        lock.lockInterruptibly();
        try {
            // 槽位被占用时循环等待，直到有读取者取走或通道关闭
            while (filled && !closed) {
                notFull.await();
            }
            // 等待期间通道被关闭：丢弃值
            if (closed) {
                dispose(value);
                return false;
            }
            // 放入值并唤醒一个读取者
            slot = value;
            filled = true;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    // ==================== 读取 ====================

    /**
     * 非阻塞读取。
     *
     * <p>若槽位有值则取出并返回；否则立即返回 null。
     *
     * @return 槽位中的值，或 null（槽位为空时）
     */
    @Override
    public T poll() {
        lock.lock();
        try {
            if (filled) {
                T value = slot;
                slot = null;
                filled = false;
                notFull.signal();   // 唤醒一个等待写入的线程
                return value;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 阻塞读取。
     *
     * <p>若槽位有值则立即取出返回；否则阻塞等待，直到有写入者放入值或通道被关闭。
     *
     * <p>若通道已关闭且槽位为空，返回 null。
     *
     * @return 槽位中的值；若通道关闭且无可读值则返回 null
     * @throws InterruptedException 若等待时被中断
     */
    @Override
    public T get() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            // 无值且未关闭时等待
            while (!filled && !closed) {
                notEmpty.await();
            }
            // 关闭且无值：返回 null
            if (closed && !filled) {
                return null;
            }
            // 取走值并唤醒一个等待写入的线程
            T value = slot;
            slot = null;
            filled = false;
            notFull.signal();
            return value;
        } finally {
            lock.unlock();
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 返回当前槽位中的元素数量（0 或 1）。
     */
    @Override
    public int size() {
        lock.lock();
        try {
            return filled ? 1 : 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回通道容量，无缓冲通道始终为 0。
     */
    @Override
    public int capacity() {
        return 0;
    }

    /**
     * 无缓冲通道永远不会满（写入者阻塞等待而非返回满）。
     */
    @Override
    public boolean isFull() {
        return false;
    }

    /**
     * 槽位中无值时返回 true。
     */
    @Override
    public boolean isEmpty() {
        lock.lock();
        try {
            return !filled;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回通道是否已关闭。
     */
    @Override
    public boolean isClosed() {
        return closed;
    }

    // ==================== 生命周期 ====================

    /**
     * 关闭通道。
     *
     * <p>关闭后所有阻塞的读取者和写入者被立即唤醒，后续写入操作被拒绝，
     * 读取操作在槽位为空时返回 null。已 handoff 到槽位中的值仍可被 {@link #poll()}
     * 或 {@link #get()} 取出。
     */
    @Override
    public void close() {
        closed = true;
        lock.lock();
        try {
            // 唤醒所有等待线程，使其检查 closed 标志后退出
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清空通道。
     *
     * <p>丢弃槽位中当前的值（通过 onDispose 回调通知），唤醒所有等待写入的线程。
     * 不影响已关闭状态。
     */
    @Override
    public void clear() {
        lock.lock();
        try {
            if (filled) {
                dispose(slot);
                slot = null;
                filled = false;
                notFull.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }
}