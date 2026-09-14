package top.kzre.krro.core.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

public final class ExecutorServiceAsyncAdapter implements AsyncExecutor {
    private final ExecutorService service;

    public ExecutorServiceAsyncAdapter(ExecutorService service) {
        this.service = service;
    }

    @Override
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try { return task.call(); }
            catch (Exception e) { throw new CompletionException(e); }
        }, service);
    }
}