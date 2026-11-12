package com.example.harness.agent;

import com.example.harness.task.TaskState;
import com.example.harness.task.TaskStore;
import org.springframework.stereotype.Component;

/** 对外提供稳定的 Agent 执行入口，当前委托统一的 AgentLoop。 */
@Component
public class AgentRuntime {

    private final AgentLoop agentLoop;
    private final TaskStore taskStore;

    public AgentRuntime(AgentLoop agentLoop, TaskStore taskStore) {
        this.agentLoop = agentLoop;
        this.taskStore = taskStore;
    }

    /** 不传 taskId 时创建新任务；传入时继续已有任务。 */
    public AgentResult run(String requestedTaskId, String userMessage) {
        TaskState task = requestedTaskId == null || requestedTaskId.isBlank()
                ? taskStore.create(userMessage)
                : taskStore.require(requestedTaskId);

        return taskStore.withTaskLock(task.taskId(), lockedTask -> {
            taskStore.start(lockedTask);
            try {
                String answer = agentLoop.run(lockedTask, userMessage);
                taskStore.complete(lockedTask);
                return new AgentResult(lockedTask.taskId(), answer);
            } catch (RuntimeException e) {
                taskStore.fail(lockedTask, e.getMessage());
                throw e;
            }
        });
    }

    public record AgentResult(String taskId, String answer) {
    }
}
