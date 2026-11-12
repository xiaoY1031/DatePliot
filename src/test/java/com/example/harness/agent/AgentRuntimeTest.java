package com.example.harness.agent;

import com.example.harness.task.TaskState;
import com.example.harness.task.TaskStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeTest {

    @Test
    void createsTaskAndCompletesIt() {
        TaskStore store = new TaskStore();
        AgentLoop loop = mock(AgentLoop.class);
        when(loop.run(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("第一轮"))).thenReturn("回答一");
        AgentRuntime runtime = new AgentRuntime(loop, store);

        AgentRuntime.AgentResult result = runtime.run(null, "第一轮");
        TaskState task = store.require(result.taskId());

        assertEquals("回答一", result.answer());
        assertEquals("第一轮", task.goal());
        assertEquals(TaskState.Status.COMPLETED, task.status());
        verify(loop).run(task, "第一轮");
    }

    @Test
    void continuesExistingTaskAndMarksFailure() {
        TaskStore store = new TaskStore();
        TaskState task = store.create("初始目标");
        AgentLoop loop = mock(AgentLoop.class);
        when(loop.run(task, "继续")).thenThrow(new IllegalStateException("模型失败"));
        AgentRuntime runtime = new AgentRuntime(loop, store);

        var error = assertThrows(IllegalStateException.class,
                () -> runtime.run(task.taskId(), "继续"));

        assertEquals("模型失败", error.getMessage());
        assertEquals(TaskState.Status.FAILED, task.status());
        assertEquals("模型失败", task.lastError());
    }

    @Test
    void rejectsUnknownTaskId() {
        AgentRuntime runtime = new AgentRuntime(mock(AgentLoop.class), new TaskStore());
        assertThrows(TaskStore.UnknownTaskException.class,
                () -> runtime.run("missing", "继续"));
    }
}
