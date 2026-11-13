package com.example.harness.context;

import com.example.harness.llm.model.LlmMessage;
import com.example.harness.llm.model.LlmToolCall;
import com.example.harness.skill.SkillLoader;
import com.example.harness.task.TaskState;
import com.example.harness.task.TaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextManagerTest {

    @TempDir
    Path temporaryDirectory;

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
    void injectsOnlySkillMetadataAndExplainsOnDemandLoading() throws IOException {
        Path skillsRoot = Files.createDirectory(temporaryDirectory.resolve("catalog"));
        Path skillDirectory = Files.createDirectory(skillsRoot.resolve("data-analysis"));
        Files.writeString(skillDirectory.resolve("SKILL.md"), """
                ---
                name: data-analysis
                description: 分析已有数据并发现趋势
                ---
                私有指令正文不能提前进入 Catalog。
                """);
        TaskStore store = new TaskStore();
        ContextManager manager = new ContextManager(
                store,
                new SkillLoader(skillsRoot.toString()),
                new ByteArrayResource("基础系统提示".getBytes(StandardCharsets.UTF_8)));
        TaskState task = store.create("分析数据");

        List<LlmMessage> messages = manager.buildMessages(task);

        assertEquals("基础系统提示", messages.get(0).content());
        assertEquals(LlmMessage.Role.SYSTEM, messages.get(1).role());
        assertTrue(messages.get(1).content().contains("data-analysis"));
        assertTrue(messages.get(1).content().contains("分析已有数据并发现趋势"));
        assertTrue(messages.get(1).content().contains("load_skill"));
        assertFalse(messages.get(1).content().contains("私有指令正文"));
        assertEquals("当前任务目标：分析数据", messages.get(2).content());
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
                emptySkillLoader(),
                new ByteArrayResource("基础系统提示".getBytes(StandardCharsets.UTF_8)));
    }

    private SkillLoader emptySkillLoader() {
        try {
            return new SkillLoader(Files.createDirectory(
                    temporaryDirectory.resolve("empty-" + System.nanoTime())).toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
