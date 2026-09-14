package top.kzre.krro.core.util;

/**
 * 合并后的任务——由 {@link CoalescedTaskRunner} 执行。
 *
 * <p>{@link CoalescedTaskRunner} 把多个等待的任务合并为一个——
 * 执行的就是这个合并结果。参数类型 {@code P} 自带合并能力
 * （{@link Mergable#merge}）——执行器据此折叠多个参数为一个。
 *
 * <p><b>典型实现</b>：
 * <pre>{@code
 * class RenderTask implements CoalescedTask<ViewportParams> {
 *     @Override
 *     public void run(ViewportParams params) {
 *         renderScene(params);
 *     }
 * }
 * }</pre>
 *
 * <p><b>与 Runnable / Callable 的区别</b>：
 * <ul>
 *   <li>{@link Runnable}——无参数</li>
 *   <li>{@link java.util.concurrent.Callable}——无参数 + 返回值</li>
 *   <li>{@code CoalescedTask<P>}——有参数（合并后的）+ 无返回值</li>
 * </ul>
 *
 * @param <P> 参数类型——必须自带合并能力
 */
@FunctionalInterface
public interface CoalescedTask<P extends Mergable<P>> {

    /**
     * 执行任务——参数是多个等待任务合并后的结果。
     *
     * <p>由 {@link CoalescedTaskRunner} 在适当时机调用——
     * 通常是执行器空闲时、或当前任务完成后。
     *
     * @param params 合并后的参数
     */
    void run(P params);
}