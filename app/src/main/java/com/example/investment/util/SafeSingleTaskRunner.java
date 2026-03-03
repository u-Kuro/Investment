package com.example.investment.util;

import static com.example.investment.util.Constants.UI_THREAD;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public class SafeSingleTaskRunner {
    @FunctionalInterface
    public interface UI { void post(Runnable callback); }
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    // For unique tasks
    private final Map<Object, Future<?>> currentUniqueTasks = new ConcurrentHashMap<>();
    private final Map<Object, Object> currentUniqueTaskIds = new ConcurrentHashMap<>();
    public void replaceUniqueTask(@NonNull final Object id, @NonNull final Consumer<UI> task) {
        final Object newUniqueTaskId = new Object();
        cancelAndReplaceCurrentUniqueTaskId(id, newUniqueTaskId);

        final UI ui = callback -> UI_THREAD.post(() -> {
            if (currentUniqueTaskIds.get(id) != newUniqueTaskId) return;
            callback.run();
        });

        currentUniqueTasks.put(id, executor.submit(() -> {
            try {
                task.accept(ui);
            } catch (Exception e) {
                handleException(e);
            } finally {
                ui.post(() -> {
                    currentUniqueTaskIds.remove(id);
                    currentUniqueTasks.remove(id);
                });
            }
        }));
    }
    public void replaceUniqueTask(@NonNull final Object id, @NonNull final Runnable task) {
        final Object newUniqueTaskId = new Object();
        cancelAndReplaceCurrentUniqueTaskId(id, newUniqueTaskId);

        currentUniqueTasks.put(id, executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                handleException(e);
            } finally {
                currentUniqueTaskIds.remove(id);
                currentUniqueTasks.remove(id);
            }
        }));
    }
    private void cancelAndReplaceCurrentUniqueTaskId(@NonNull final Object id, final @NonNull Object newUniqueTaskId) {
        currentUniqueTaskIds.put(id, newUniqueTaskId);
        final Future<?> oldUniqueTask = currentUniqueTasks.remove(id);
        if (oldUniqueTask != null && !oldUniqueTask.isDone()) {
            oldUniqueTask.cancel(true);
        }
    }
    /** @noinspection unused*/
    public void cancelCurrentUniqueTask(@NonNull final Object id) {
        currentUniqueTaskIds.remove(id);
        final Future<?> oldUniqueTask = currentUniqueTasks.remove(id);
        if (oldUniqueTask != null && !oldUniqueTask.isDone()) {
            oldUniqueTask.cancel(true);
        }
    }

    // For general task
    private Future<?> currentTask;
    private volatile Object currentTaskId = new Object();
    /** @noinspection unused*/
    public void replaceTask(@NonNull final Consumer<UI> task) {
        final Object newTaskId = new Object();
        cancelAndReplaceCurrentTaskId(newTaskId);

        final UI ui = callback -> UI_THREAD.post(() -> {
            if (currentTaskId != newTaskId) return;
            callback.run();
        });

        currentTask = executor.submit(() -> {
            try {
                task.accept(ui);
            } catch (Exception e) {
                handleException(e);
            }
        });
    }
    /** @noinspection unused*/
    public void replaceTask(final @NonNull Runnable task) {
        cancelCurrentTask();
        currentTask = executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                handleException(e);
            }
        });
    }
    private void cancelAndReplaceCurrentTaskId(final @NonNull Object newTaskId) {
        currentTaskId = newTaskId;
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }
    }
    public void cancelCurrentTask() {
        currentTaskId = null;
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }
    }

    // For persistent task
    public void execute(@NonNull final Consumer<UI> task) {
        final UI ui = UI_THREAD::post;
        executor.submit(() -> {
            try {
                task.accept(ui);
            } catch (Exception e) {
                handleException(e);
            }
        });
    }
    /** @noinspection unused*/
    public void execute(final @NonNull Runnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                handleException(e);
            }
        });
    }

    public void cancelAll() {
        currentUniqueTaskIds.clear();
        cancelCurrentTask();
        currentUniqueTasks.values().forEach(uniqueTask -> uniqueTask.cancel(true));
        currentUniqueTasks.clear();
    }
    /** @noinspection unused*/
    public void shutdownNow() {
        currentUniqueTaskIds.clear();
        cancelCurrentTask();
        currentUniqueTasks.values().forEach(uniqueTask -> uniqueTask.cancel(true));
        currentUniqueTasks.clear();
        executor.shutdownNow();
    }

    private void handleException(Exception e) {
        if (Thread.currentThread().isInterrupted() || e.getCause() instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        } else {
            Logger.error(e.getMessage(), e);
        }
    }
}