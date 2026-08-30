package top.kzre.krro.core.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按分类键保留最新任务并串行执行的执行器。
 * 每个键只保留最新提交的任务，旧任务被丢弃。
 * 所有任务在单一线程中顺序执行。
 */
public final class LastestTaskExecutor {
    private final ConcurrentHashMap<Object, TaskParams> tasks = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;
    private final TaskDefinition taskDef;

    public interface TaskParams {
        Object key();
    }

    public interface TaskDefinition {
        TaskParams mergeTask(TaskParams current, TaskParams newParams);
        void runTask(TaskParams params);
    }

    public LastestTaskExecutor(TaskDefinition taskDef) {
        this.taskDef = taskDef;
        worker = new Thread(() -> {
            while (running.get()) {
                try {
                    Object key = queue.take();
                    TaskParams params = tasks.remove(key);
                    if (params != null) {
                        taskDef.runTask(params);
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

    public void submit(Object key, TaskParams params) {
        if (!running.get()) {
            throw new IllegalStateException("Executor is shut down");
        }
        tasks.compute(key, (k, old) -> {
            if (old == null) {
                queue.offer(key);
                return params;
            } else {
                return taskDef.mergeTask(old, params);
            }
        });
    }

    public void shutdown() {
        running.set(false);
        worker.interrupt();
    }

    public void awaitTermination() throws InterruptedException {
        worker.join();
    }
}