package top.kzre.krro.core.util;

/**
 * 可合并的值——定义 {@code this} 与另一个同类型值如何合并为一个。
 *
 * <p>命名沿用 Java 功能接口惯例（{@code -able} 后缀）：
 * {@link Comparable} 表示「可以比较的」——{@code a.compareTo(b)}；
 * {@code Mergable} 表示「可以合并的」——{@code a.merge(b)}。
 *
 * <p><b>合并语义</b>：{@code this} 是已累积的值，参数 {@code other}
 * 是新到的值——返回合并结果。实现应返回新值——不修改 {@code this}
 * 或 {@code other}——除非明确文档说明允许可变。
 *
 * <p><b>必须满足结合律</b>：
 * {@code a.merge(b).merge(c).equals(a.merge(b.merge(c)))}。
 * 这是并发合并正确性的前提——多个任务按任意顺序合并结果一致。
 *
 * <p>典型实现：
 * <ul>
 *   <li>保留最新——{@code (this, other) -> other}</li>
 *   <li>累加——{@code (this, other) -> this + other}</li>
 *   <li>合并集合——{@code (this, other) -> union(this, other)}</li>
 * </ul>
 *
 * <p>用法：
 * <pre>{@code
 * class ViewportParams implements Mergable<ViewportParams> {
 *     private final float x, y, scale;
 *
 *     @Override
 *     public ViewportParams merge(ViewportParams other) {
 *         return new ViewportParams(
 *             other.x, other.y, other.scale);   // 保留最新
 *     }
 * }
 *
 * // 调用方
 * ViewportParams merged = current.merge(incoming);
 * }</pre>
 */
@FunctionalInterface
public interface Mergable<T> {

    /**
     * 把 {@code other} 合并进 {@code this}——返回合并结果。
     *
     * <p>{@code this} 是已累积的值，{@code other} 是新到的值。
     *
     * @param other 新到的值
     * @return 合并结果——通常是新对象
     */
    T merge(T other);
}