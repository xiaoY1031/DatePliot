package com.example.harness.context;

import com.example.harness.llm.model.LlmMessage;
import com.example.harness.llm.model.LlmToolCall;
import com.example.harness.task.TaskState;
import com.example.harness.task.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextManagerTest {

    @Test
    void buildsSystemTaskAndConversationContextInOrder() {
        TaskStore store = new TaskStore();
        ContextManager manager = manager(store);
        TaskState task = store.create("分析项目");
        manager.appendUserMessage(task, "先查看项目");
        manager.appendFinalAnswer(task, LlmMessage.assistant("项目已查看", List.of()));

        List<LlmMessage> messages = manager.buildMessages(task);

        assertEquals(LlmMessage.Role.SYSTEM, messages.get(0).role());
        assertEquals("基础系统提示", messages.get(0).content());
        assertEquals("当前任务目标：分析项目", messages.get(1).content());
        assertEquals(LlmMessage.Role.USER, messages.get(2).role());
        assertEquals(LlmMessage.Role.ASSISTANT, messages.get(3).role());
        assertEquals(2, task.conversationHistory().size());
    }

    @Test
    void savesCompleteToolExchangeWithMatchingIds() {
        TaskStore store = new TaskStore();
        ContextManager manager = manager(store);
        TaskState task = store.create("查看项目和目录");
        LlmMessage assistant = LlmMessage.assistant(null, List.of(
                new LlmToolCall("call_1", "function", "get_project_info", "{}"),
                new LlmToolCall("call_2", "function", "list_files", "{\"path\":\".\"}")));

        manager.appendToolExchange(task, assistant, List.of(
                LlmMessage.tool("call_1", "项目信息"),
                LlmMessage.tool("call_2", "目录信息")));

        List<LlmMessage> history = task.conversationHistory();
        assertEquals(3, history.size());
        assertEquals("call_1", history.get(1).toolCallId());
        assertEquals("call_2", history.get(2).toolCallId());
        assertEquals(2, task.toolCallCount());
    }

    @Test
    void rejectsIncompleteOrDuplicateToolResultsWithoutWritingHistory() {
        TaskStore store = new TaskStore();
        ContextManager manager = manager(store);
        TaskState task = store.create("查看项目和目录");
        LlmMessage assistant = LlmMessage.assistant(null, List.of(
                new LlmToolCall("call_1", "function", "get_project_info", "{}"),
                new LlmToolCall("call_2", "function", "list_files", "{\"path\":\".\"}")));

        assertThrows(IllegalArgumentException.class,
                () -> manager.appendToolExchange(task, assistant,
                        List.of(LlmMessage.tool("call_1", "项目信息"))));
        assertThrows(IllegalArgumentException.class,
                () -> manager.appendToolExchange(task, assistant, List.of(
                        LlmMessage.tool("call_1", "项目信息"),
                        LlmMessage.tool("call_1", "重复结果"))));
        assertEquals(List.of(), task.conversationHistory());
    }

    @Test
    void rejectsDuplicateIdsInAssistantToolCalls() {
        TaskStore store = new TaskStore();
        ContextManager manager = manager(store);
        TaskState task = store.create("重复调用");
        LlmMessage assistant = LlmMessage.assistant(null, List.of(
                new LlmToolCall("call_1", "function", "get_project_info", "{}"),
                new LlmToolCall("call_1", "function", "list_files", "{\"path\":\".\"}")));

        assertThrows(IllegalArgumentException.class,
                () -> manager.appendToolExchange(task, assistant,
                        List.of(LlmMessage.tool("call_1", "结果"))));
        assertEquals(List.of(), task.conversationHistory());
    }

    @Test
    void keepsDifferentTaskHistoriesIsolatedAndImmutable() {
        TaskStore store = new TaskStore();
        ContextManager manager = manager(store);
        TaskState first = store.create("任务一");
        TaskState second = store.create("任务二");
        manager.appendUserMessage(first, "第一条消息");

        assertEquals(1, first.conversationHistory().size());
        assertEquals(0, second.conversationHistory().size());
        assertThrows(UnsupportedOperationException.class,
                () -> first.conversationHistory().add(LlmMessage.user("非法修改")));
    }

    private ContextManager manager(TaskStore store) {
        return new ContextManager(store,
                new ByteArrayResource("基础系统提示".getBytes(StandardCharsets.UTF_8)));
    }
}
