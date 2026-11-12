package com.example.harness.task;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskStoreTest {

    @Test
    void createsUniqueTasksAndTracksStateAndProgress() {
        TaskStore store = new TaskStore();
        TaskState first = store.create("目标一");
        TaskState second = store.create("目标二");

        assertNotEquals(first.taskId(), second.taskId());
        store.start(first);
        store.recordTurn(first);
        store.recordModelRequest(first);
        store.recordToolCalls(first, 2);
        store.complete(first);

        assertEquals(TaskState.Status.COMPLETED, first.status());
        assertEquals(1, first.turnCount());
        assertEquals(1, first.modelRequestCount());
        assertEquals(2, first.toolCallCount());
    }

    @Test
    void recordsFailureAndRejectsUnknownTask() {
        TaskStore store = new TaskStore();
        TaskState task = store.create("失败任务");
        store.start(task);
        store.fail(task, "模型失败");

        assertEquals(TaskState.Status.FAILED, task.status());
        assertEquals("模型失败", task.lastError());
        assertThrows(TaskStore.UnknownTaskException.class, () -> store.require("missing"));
    }

    @Test
    void serializesConcurrentOperationsForSameTask() throws Exception {
        TaskStore store = new TaskStore();
        TaskState task = store.create("并发任务");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch allowFirstFinish = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        var first = pool.submit(() -> store.withTaskLock(task.taskId(), locked -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            firstEntered.countDown();
            await(allowFirstFinish);
            active.decrementAndGet();
            return null;
        }));
        firstEntered.await(2, TimeUnit.SECONDS);
        var second = pool.submit(() -> store.withTaskLock(task.taskId(), locked -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            active.decrementAndGet();
            return null;
        }));

        Thread.sleep(100);
        allowFirstFinish.countDown();
        first.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, maxActive.get());
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
