package top.kzre.krro.core.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按分类键保留最新任务并串行执行的执行器。
 * 每个键只保留最新提交的任务，旧任务被丢弃。
 * 所有任务在单一线程中顺序执行。
 */
public final class LastTaskExecutor {
    private final ConcurrentHashMap<Object, Runnable> tasks = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    public LastTaskExecutor() {
        worker = new Thread(() -> {
            while (running.get()) {
                try {
                    Object key = queue.take();                  // 阻塞等待键
                    Runnable task = tasks.remove(key);         // 取出并移除任务
                    if (task != null) {
                        task.run();                             // 执行最新任务
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 提交一个任务，与给定的键关联。
     * 如果该键已有未执行的任务，旧任务将被新任务覆盖。
     *
     * @param key  分类键
     * @param task 要执行的任务
     * @throws IllegalStateException 如果执行器已关闭
     */
    public void submit(Object key, Runnable task) {
        if (!running.get()) {
            throw new IllegalStateException("Executor is shut down");
        }
        tasks.put(key, task);           // 覆盖旧任务
        queue.offer(key);               // 通知工作线程（即使键已存在，offer 仍然成功）
    }

    /**
     * 关闭执行器，不再接受新任务。
     * 已提交的任务仍会执行完毕。
     */
    public void shutdown() {
        running.set(false);
        worker.interrupt();
    }

    /**
     * 等待执行器终止（即工作线程结束）。
     */
    public void awaitTermination() throws InterruptedException {
        worker.join();
    }
}