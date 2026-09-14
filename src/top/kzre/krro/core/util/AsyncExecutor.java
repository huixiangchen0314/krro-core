package top.kzre.krro.core.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * 异步执行器——返回 Future 的执行器抽象。
 *
 * <p>与 {@link java.util.concurrent.Executor} 的区别：
 * <ul>
 *   <li>返回 {@link CompletableFuture}——统一消费路径</li>
 *   <li>支持 Callable——拿到返回值</li>
 *   <li>失败通过 Future 承载——不用 try/catch</li>
 * </ul>
 *
 * <p>与 {@link java.util.concurrent.ExecutorService} 的区别：
 * <ul>
 *   <li>只关注「提交任务」——不管线程池生命周期</li>
 *   <li>不暴露 shutdown / awaitTermination——由具体实现决定</li>
 * </ul>
 *
 * <h2>语义</h2>
 * <ul>
 *   <li><b>异步</b>——submit 立即返回——任务在后台执行</li>
 *   <li><b>可等待</b>——返回的 Future 支持 get / join / 链式组合</li>
 *   <li><b>拒绝路径</b>——队列满等拒绝场景通过 failed future 承载</li>
 *   <li><b>不取消</b>——submit 本身不取消任务——取消是调用方的事</li>
 * </ul>
 *
 * <h2>典型实现</h2>
 * <ul>
 *   <li>{@link SerialExecutor}——串行化到单线程</li>
 *   <li>{@code ForkJoinPool}——并行任务</li>
 *   <li>{@code ExecutorService}——线程池</li>
 * </ul>
 */
public interface AsyncExecutor {

    /**
     * 提交一个有返回值的任务。
     *
     * <p>返回的 Future：
     * <ul>
     *   <li>成功——以 task 的返回值完成</li>
     *   <li>失败——以 task 抛出的异常完成（completeExceptionally）</li>
     *   <li>拒绝——以 RejectedExecutionException 完成</li>
     * </ul>
     *
     * @param task 要执行的任务
     * @param <T>  返回值类型
     * @return 承载任务结果的 Future
     * @throws NullPointerException  task 为 null
     * @throws IllegalStateException 执行器已关闭
     */
    <T> CompletableFuture<T> submit(Callable<T> task);

    /**
     * 提交一个无返回值的任务。
     *
     * <p>等价于 {@code submit(() -> { task.run(); return null; })}。
     *
     * @param task 要执行的任务
     * @return 承载 null 的 Future——完成后可用于等待
     */
    default CompletableFuture<Void> submit(Runnable task) {
        return submit(() -> {
            task.run();
            return null;
        });
    }

    /**
     * 适配为 {@link java.util.concurrent.Executor}。
     *
     * <p>返回的 Executor 的 execute 方法内部调 submit——队列满时
     * 同步抛 RejectedExecutionException——满足 Executor 契约。
     */
    default Executor asExecutor() {
        return command -> {
            CompletableFuture<Void> future = submit(command);
            // 检查拒绝——Executor 契约要求同步抛
            if (future.isCompletedExceptionally()) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException) {
                        throw (RuntimeException)cause;
                    }
                    if (cause instanceof Error) {
                        throw (Error)cause;
                    }
                    throw new RuntimeException(cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted while awaiting rejection", e);
                }
            }
        };
    }
}