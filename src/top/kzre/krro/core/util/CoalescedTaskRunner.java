package top.kzre.krro.core.util;

import java.util.concurrent.CompletableFuture;

/**
 * 合并任务执行器——基类。
 *
 * <p>持有 task（{@link CoalescedTask}）——负责「按 key 路由 params、
 * 合并等待的 params、执行 task」的逻辑。子类实现 {@link #submit}
 * ——定义具体的路由和合并策略。
 *
 * <p>子类通过 {@code protected task} 字段访问任务处理器——
 * 通常只调 {@code task.run(mergedParams)}。
 *
 * @param <P> 参数类型——必须自带合并能力
 */
public abstract class CoalescedTaskRunner<P extends Mergable<P>> {

    /**
     * 任务处理器——子类可见——通常是「怎么执行合并后的 params」。
     */
    protected final CoalescedTask<P> task;

    protected CoalescedTaskRunner(CoalescedTask<P> task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        this.task = task;
    }

    /**
     * 按 key 提交参数——合并执行器负责路由、合并、异步执行。
     *
     * <p>语义：
     * <ul>
     *   <li>同一 key 的多个提交——params 按 {@link Mergable#merge}
     *       合并——最终执行一次 task</li>
     *   <li>不同 key——独立执行——可并行</li>
     *   <li>返回 future——在最终 task 执行完成后完成</li>
     * </ul>
     *
     * @param key    路由 key——不能为 null
     * @param params 参数——不能为 null
     * @return 承载 task 执行结果的 Future
     * @throws NullPointerException key 或 params 为 null
     */
    public abstract CompletableFuture<Void> submit(Object key, P params);
}