package com.example.harness.task;

import com.example.harness.llm.model.LlmMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 基于内存的任务存储，并为每个 taskId 提供串行执行锁。 */
@Component
public class TaskStore {

    private final ConcurrentMap<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> taskLocks = new ConcurrentHashMap<>();

    public TaskState create(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("任务目标不能为空");
        }
        String taskId = UUID.randomUUID().toString();
        TaskState task = new TaskState(taskId, goal);
        tasks.put(taskId, task);
        taskLocks.put(taskId, new Object());
        return task;
    }

    public TaskState require(String taskId) {
        TaskState task = tasks.get(taskId);
        if (task == null) {
            throw new UnknownTaskException(taskId);
        }
        return task;
    }

    /** 同一 taskId 的请求在任务锁内串行执行，避免历史消息交错写入。 */
    public <T> T withTaskLock(String taskId, TaskOperation<T> operation) {
        TaskState task = require(taskId);
        Object lock = taskLocks.get(taskId);
        synchronized (lock) {
            return operation.apply(task);
        }
    }

    public void start(TaskState task) {
        task.start();
    }

    public void complete(TaskState task) {
        task.complete();
    }

    public void fail(TaskState task, String error) {
        task.fail(error == null ? "未知错误" : error);
    }

    public List<LlmMessage> history(TaskState task) {
        return task.conversationHistory();
    }

    public void append(TaskState task, LlmMessage message) {
        task.append(message);
    }

    public void appendAll(TaskState task, List<LlmMessage> messages) {
        task.appendAll(messages);
    }

    public void recordTurn(TaskState task) {
        task.incrementTurnCount();
    }

    public void recordModelRequest(TaskState task) {
        task.incrementModelRequestCount();
    }

    public void recordToolCalls(TaskState task, int count) {
        task.addToolCalls(count);
    }

    @FunctionalInterface
    public interface TaskOperation<T> {
        T apply(TaskState task);
    }

    public static class UnknownTaskException extends IllegalArgumentException {
        public UnknownTaskException(String taskId) {
            super("任务不存在：" + taskId);
        }
    }
}
