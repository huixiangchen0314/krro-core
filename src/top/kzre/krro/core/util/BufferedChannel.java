package top.kzre.krro.core.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 有缓冲的阻塞通道，提供 FIFO 顺序的发布-订阅语义。
 *
 * <p>本类类似于 Go 语言中带缓冲的 channel，但不依赖特定线程模型。
 * 所有读写操作均保证线程安全，内部通过 {@link ReentrantLock} 与
 * {@link Condition} 实现阻塞与唤醒。
 *
 * <h2>核心特性</h2>
 * <ul>
 *   <li>容量固定，构造后不可变更</li>
 *   <li>{@link #send(Object)} 非阻塞：通道满时丢弃最旧元素</li>
 *   <li>{@link #offer(Object)} 阻塞：通道满时等待空位</li>
 *   <li>{@link #poll()} 非阻塞读取，队列空时返回 {@code null}</li>
 *   <li>{@link #get()} 阻塞读取，通道关闭且为空时返回 {@code null}</li>
 *   <li>{@link #close()} 关闭通道后仍可消费已入队元素</li>
 *   <li>{@link #clear()} 清空队列并触发回收回调，不关闭通道</li>
 * </ul>
 *
 * <h2>内存一致性</h2>
 * <p>对同一个 {@code BufferedChannel} 实例，写操作（{@link #send} /
 * {@link #offer}）的 happen-before 关系保证后续读操作（{@link #get} /
 * {@link #poll}）能观察到对应元素。
 *
 * <h2>使用注意</h2>
 * <p>{@code null} 元素不被允许，否则抛出 {@link NullPointerException}。
 *
 * @param <T> 通道中传输的元素类型
 */
public final class BufferedChannel<T> implements IChannel<T> {

    private final int capacity;
    private final Deque<T> queue = new ArrayDeque<>();
    private final Consumer<T> onDispose;
    private boolean closed = false;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    /**
     * 构造一个指定容量的有缓冲通道。
     *
     * @param capacity  通道容量，必须大于 0
     * @param onDispose 元素被丢弃或清理时的回调；
     *                  为 {@code null} 时使用空实现（no-op）
     * @throws IllegalArgumentException 如果 {@code capacity <= 0}
     */
    public BufferedChannel(int capacity, Consumer<T> onDispose) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must > 0");
        }
        this.capacity = capacity;
        this.onDispose = onDispose != null ? onDispose : t -> {};
    }

    /**
     * 释放被丢弃或清理的元素资源。
     * 仅在内部调用，不对外暴露。
     *
     * @param value 待释放的元素，可能为 {@code null}（此时无操作）
     */
    private void dispose(T value) {
        if (value != null) {
            onDispose.accept(value);
        }
    }

    // ==================== 写入 ====================

    /**
     * 非阻塞地发送元素到通道。
     *
     * <p>如果通道已关闭，元素将被 {@link #onDispose} 回收且返回 {@code false}。
     * 如果通道已满，队头（最旧）元素将被移出并触发 {@code onDispose}，
     * 随后新元素入队。
     *
     * @param value 待发送的元素，不允许为 {@code null}
     * @return {@code true} 表示元素已入队；{@code false} 表示通道已关闭
     * @throws NullPointerException 如果 {@code value} 为 {@code null}
     */
    @Override
    public boolean send(T value) {
        if (value == null) throw new NullPointerException("value");
        lock.lock();
        try {
            if (closed) {
                dispose(value);
                return false;
            }
            if (queue.size() >= capacity) {
                dispose(queue.pollFirst());
            }
            queue.addLast(value);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 阻塞地发送元素到通道。
     *
     * <p>如果通道已满，当前线程将阻塞直到出现空位或通道被关闭。
     * 如果通道已关闭，元素将被 {@code onDispose} 回收且返回 {@code false}。
     *
     * @param value 待发送的元素，不允许为 {@code null}
     * @return {@code true} 表示元素已入队；{@code false} 表示通道已关闭
     * @throws NullPointerException  if {@code value} is {@code null}
     * @throws InterruptedException 等待时空位时被中断
     */
    @Override
    public boolean offer(T value) throws InterruptedException {
        if (value == null) throw new NullPointerException("value");
        lock.lock();
        try {
            if (closed) {
                dispose(value);
                return false;
            }
            while (queue.size() >= capacity && !closed) {
                notFull.await();
            }
            if (closed) {
                dispose(value);
                return false;
            }
            queue.addLast(value);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    // ==================== 读取 ====================

    /**
     * 非阻塞地取出并移除队头元素。
     *
     * <p>按 FIFO 顺序返回最旧的元素。如果队列为空，立即返回 {@code null}。
     * 成功取出后会唤醒一个因通道满而等待的写线程。
     *
     * @return 队头元素，或 {@code null}（队列为空时）
     */
    @Override
    public T poll() {
        lock.lock();
        try {
            T value = queue.pollFirst();
            if (value != null) {
                notFull.signal();
            }
            return value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 阻塞地取出并移除队头元素。
     *
     * <p>如果队列为空，当前线程将阻塞直到有元素入队或通道被关闭。
     * 当通道关闭且队列为空时返回 {@code null}，表示不会再有新元素到来。
     *
     * @return 队头元素，或 {@code null}（通道已关闭且队列为空）
     * @throws InterruptedException 等待时被中断
     */
    @Override
    public T get() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty() && !closed) {
                notEmpty.await();
            }
            T value = queue.pollFirst();
            if (value != null) {
                notFull.signal();
            }
            return value;
        } finally {
            lock.unlock();
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 返回当前通道中的元素数量。
     *
     * <p>该操作为快照值，返回时可能已被其他线程修改。
     *
     * @return 当前队列中的元素个数
     */
    @Override
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回通道的容量。
     *
     * @return 构造时指定的容量值
     */
    @Override
    public int capacity() {
        return capacity;
    }

    /**
     * 判断通道是否已满。
     *
     * <p>当 {@code size() >= capacity} 时返回 {@code true}。
     *
     * @return {@code true} 如果通道已满
     */
    @Override
    public boolean isFull() {
        lock.lock();
        try {
            return queue.size() >= capacity;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 判断通道是否为空。
     *
     * @return {@code true} 如果队列中没有元素
     */
    @Override
    public boolean isEmpty() {
        lock.lock();
        try {
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 判断通道是否已关闭。
     *
     * <p>关闭后的通道不再接受新元素写入，但已入队元素仍可正常消费。
     *
     * @return {@code true} 如果 {@link #close()} 已被调用
     */
    @Override
    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 关闭通道。
     *
     * <p>关闭后：
     * <ul>
     *   <li>所有阻塞在 {@link #get()} 或 {@link #offer(Object)} 的线程被唤醒</li>
     *   <li>后续写操作（{@link #send} / {@link #offer}）失败并触发 {@code onDispose}</li>
     *   <li>已入队元素仍可正常通过 {@link #get()} / {@link #poll()} 消费</li>
     *   <li>重复调用无效</li>
     * </ul>
     */
    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清空通道中的所有元素。
     *
     * <p>对每个元素依次触发 {@code onDispose} 回调，然后唤醒所有因通道满而
     * 阻塞的写线程。该操作不会关闭通道，清空后仍可接受新元素。
     */
    @Override
    public void clear() {
        lock.lock();
        try {
            while (!queue.isEmpty()) {
                dispose(queue.pollFirst());
            }
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }
}