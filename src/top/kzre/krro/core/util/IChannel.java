package top.kzre.krro.core.util;

/**
 * 通道接口，定义发布订阅模型的统一契约。
 * <p>
 * 实现类分为两类：
 * <ul>
 *   <li><b>有缓冲</b>（如 {@link BufferedChannel}）：内部维护队列，满时行为由实现决定</li>
 *   <li><b>无缓冲</b>（如 {@link BlockingChannel}）：不存储元素，读写必须配对</li>
 * </ul>
 *
 * <p><b>核心语义约定：</b>
 * <ul>
 *   <li>{@code send} —— 非阻塞写入，满时或无人接收时由实现决定丢弃策略</li>
 *   <li>{@code offer} —— 阻塞写入，等待通道有空位或接收者配对</li>
 *   <li>{@code poll} —— 非阻塞读取，无数据时返回 {@code null}</li>
 *   <li>{@code get} —— 阻塞读取，等待数据到达</li>
 *   <li>{@code close} —— 关闭通道，唤醒所有等待者，后续写入失败，已入队数据仍可消费</li>
 *   <li>{@code clear} —— 清空待消费数据并触发 {@code onDispose}，<b>不关闭通道</b></li>
 * </ul>
 *
 * <p><b>线程安全性：</b>所有实现必须保证线程安全。
 *
 * @param <T> 元素类型
 */
public interface IChannel<T> extends AutoCloseable {

    // ==================== 写入 ====================

    /**
     * 非阻塞发送元素。
     * <p>
     * 具体行为取决于实现：
     * <ul>
     *   <li>有缓冲通道：若已满，丢弃最旧元素（触发 {@code onDispose}）后将当前元素入队</li>
     *   <li>无缓冲通道：若当前无接收者等待，直接丢弃元素（触发 {@code onDispose}）</li>
     * </ul>
     * 若通道已关闭，元素将被丢弃（触发 {@code onDispose}）并返回 {@code false}。
     *
     * @param value 待发送的元素，不允许为 {@code null}
     * @return {@code true} 成功入队或配对成功；{@code false} 通道已关闭或无人接收
     * @throws NullPointerException 如果 {@code value} 为 {@code null}
     */
    boolean send(T value);

    /**
     * 阻塞发送元素。
     * <p>
     * 具体行为取决于实现：
     * <ul>
     *   <li>有缓冲通道：若已满，阻塞等待直到有空位</li>
     *   <li>无缓冲通道：阻塞等待直到有接收者调用 {@code get()}</li>
     * </ul>
     * 若等待期间通道被关闭，返回 {@code false}。
     *
     * @param value 待发送的元素，不允许为 {@code null}
     * @return {@code true} 成功入队或配对成功；{@code false} 通道已关闭
     * @throws NullPointerException 如果 {@code value} 为 {@code null}
     * @throws InterruptedException 等待时被中断
     */
    boolean offer(T value) throws InterruptedException;

    // ==================== 读取 ====================

    /**
     * 非阻塞读取元素。
     * <p>
     * 按 FIFO 顺序取出一个元素。若当前无可用数据，返回 {@code null}。
     * 无缓冲通道可能不支持此操作（始终返回 {@code null}）。
     *
     * @return 元素，或 {@code null}（无数据可用）
     */
    T poll();

    /**
     * 阻塞读取元素。
     * <p>
     * 若当前无可用数据，阻塞等待直到有元素入队或通道关闭。
     * 通道关闭且队列为空（或无缓冲无等待中发送者）时返回 {@code null}。
     *
     * @return 元素，或 {@code null}（通道已关闭且无数据）
     * @throws InterruptedException 等待时被中断
     */
    T get() throws InterruptedException;

    // ==================== 状态 ====================

    /**
     * 返回当前通道中的元素数量。
     *
     * @return 当前元素数
     */
    int size();

    /**
     * 返回通道容量。
     *
     * @return 容量值；无缓冲通道返回 0
     */
    int capacity();

    /**
     * 判断通道是否已满。
     * <p>
     * 对于无缓冲通道，当有发送者在等待接收者时返回 {@code true}。
     *
     * @return {@code true} 如果通道已满
     */
    boolean isFull();

    /**
     * 判断通道是否为空。
     *
     * @return {@code true} 如果通道中无元素
     */
    boolean isEmpty();

    /**
     * 判断通道是否已关闭。
     *
     * @return {@code true} 如果已调用 {@link #close()}
     */
    boolean isClosed();

    // ==================== 生命周期 ====================

    /**
     * 关闭通道。
     * <p>
     * 关闭后：
     * <ul>
     *   <li>所有阻塞的读写操作被唤醒</li>
     *   <li>后续写入操作失败并返回 {@code false}</li>
     *   <li>已入队元素仍可被消费（直到取完）</li>
     *   <li>已关闭的通道不可重新打开</li>
     * </ul>
     */
    @Override
    void close();

    /**
     * 清空通道中所有待消费的数据。
     * <p>
     * 对每个被丢弃的元素触发 {@code onDispose} 回调。
     * <b>此方法不关闭通道</b>，清空后仍可正常读写。
     * 与 {@link #close()} 的语义不同，适用于需要强制丢弃数据但保持通道存活的场景。
     */
    void clear();
}