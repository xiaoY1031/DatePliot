package com.example.harness.agent;

import com.example.harness.context.ContextManager;
import com.example.harness.llm.LlmClient;
import com.example.harness.llm.model.LlmMessage;
import com.example.harness.llm.model.LlmRequest;
import com.example.harness.llm.model.LlmResponse;
import com.example.harness.llm.model.LlmToolCall;
import com.example.harness.skill.SkillLoader;
import com.example.harness.task.TaskState;
import com.example.harness.task.TaskStore;
import com.example.harness.tool.ProjectInfoTool;
import com.example.harness.tool.ToolExecutor;
import com.example.harness.tool.ToolRegistry;
import com.example.harness.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLoopTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesFinalAssistantAnswerAndUsesSystemContext() {
        Fixture fixture = fixture();
        when(fixture.client.complete(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            assertEquals(LlmMessage.Role.SYSTEM, request.messages().get(0).role());
            assertEquals("当前任务目标：你好", request.messages().get(1).content());
            assertEquals(LlmMessage.Role.USER, request.messages().get(2).role());
            return response("你好");
        });

        assertEquals("你好", fixture.loop.run(fixture.task, "你好"));
        assertEquals(2, fixture.task.conversationHistory().size());
        assertEquals(LlmMessage.Role.ASSISTANT,
                fixture.task.conversationHistory().get(1).role());
        assertEquals(1, fixture.task.turnCount());
        assertEquals(1, fixture.task.modelRequestCount());
    }

    @Test
    void reusesCompleteConversationHistoryOnSecondTurn() {
        Fixture fixture = fixture("第一轮问题");
        AtomicInteger requestCount = new AtomicInteger();
        when(fixture.client.complete(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            if (requestCount.getAndIncrement() == 0) {
                assertEquals(3, request.messages().size());
                return response("第一轮回答");
            }
            assertEquals(5, request.messages().size());
            assertEquals("第一轮问题", request.messages().get(2).content());
            assertEquals("第一轮回答", request.messages().get(3).content());
            assertEquals("第二轮问题", request.messages().get(4).content());
            return response("第二轮回答");
        });

        assertEquals("第一轮回答", fixture.loop.run(fixture.task, "第一轮问题"));
        assertEquals("第二轮回答", fixture.loop.run(fixture.task, "第二轮问题"));
        assertEquals(4, fixture.task.conversationHistory().size());
        assertEquals(2, fixture.task.turnCount());
        assertEquals(2, fixture.task.modelRequestCount());
    }

    @Test
    void preservesMultipleToolCallsAndMatchingResultsAcrossRequests() {
        Fixture fixture = fixture("查看项目和目录");
        AtomicInteger requestCount = new AtomicInteger();
        when(fixture.client.complete(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            if (requestCount.getAndIncrement() == 0) {
                return new LlmResponse(LlmMessage.assistant(null, List.of(
                        new LlmToolCall("call_1", "function", "get_project_info", "{}"),
                        new LlmToolCall("call_2", "function", "list_files", "{\"path\":\".\"}"))),
                        "tool_calls");
            }
            assertEquals(6, request.messages().size());
            assertEquals("call_1", request.messages().get(4).toolCallId());
            assertEquals("call_2", request.messages().get(5).toolCallId());
            return response("完成");
        });
        when(fixture.executor.execute("call_1", "get_project_info", "{}"))
                .thenReturn(ToolResult.success("项目信息"));
        when(fixture.executor.execute("call_2", "list_files", "{\"path\":\".\"}"))
                .thenReturn(ToolResult.success("目录信息"));

        assertEquals("完成", fixture.loop.run(fixture.task, "查看项目和目录"));
        assertEquals(5, fixture.task.conversationHistory().size());
        assertEquals(2, fixture.task.toolCallCount());
        assertEquals(2, fixture.task.modelRequestCount());
    }

    @Test
    void returnsToolErrorsToContextAndContinues() {
        Fixture fixture = fixture("错误工具");
        when(fixture.client.complete(any()))
                .thenReturn(new LlmResponse(LlmMessage.assistant(null, List.of(
                        new LlmToolCall("call_unknown", "function", "missing_tool", "{}"))),
                        "tool_calls"))
                .thenAnswer(invocation -> {
                    LlmRequest request = invocation.getArgument(0);
                    assertThat(request.messages().get(4).content(), containsString("UNKNOWN_TOOL"));
                    return response("已处理错误");
                });
        when(fixture.executor.execute("call_unknown", "missing_tool", "{}"))
                .thenReturn(ToolResult.failure("UNKNOWN_TOOL", "不支持的工具"));

        assertEquals("已处理错误", fixture.loop.run(fixture.task, "错误工具"));
    }

    @Test
    void stopsAfterTenModelRequests() {
        Fixture fixture = fixture("循环任务");
        when(fixture.client.complete(any())).thenReturn(new LlmResponse(
                LlmMessage.assistant(null, List.of(
                        new LlmToolCall("call_1", "function", "get_project_info", "{}"))),
                "tool_calls"));
        when(fixture.executor.execute("call_1", "get_project_info", "{}"))
                .thenReturn(ToolResult.success("项目信息"));

        var error = assertThrows(IllegalStateException.class,
                () -> fixture.loop.run(fixture.task, "循环任务"));

        assertEquals("Agent 超过最大循环次数 10 轮", error.getMessage());
        assertEquals(10, fixture.task.modelRequestCount());
        verify(fixture.client, times(10)).complete(any());
    }

    private Fixture fixture() {
        return fixture("你好");
    }

    private Fixture fixture(String goal) {
        TaskStore store = new TaskStore();
        ContextManager contextManager = new ContextManager(
                store,
                emptySkillLoader(),
                new ByteArrayResource("系统提示".getBytes(StandardCharsets.UTF_8)));
        LlmClient client = mock(LlmClient.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        AgentLoop loop = new AgentLoop(
                client,
                executor,
                new ToolRegistry(List.of(new ProjectInfoTool())),
                contextManager);
        return new Fixture(loop, client, executor, store.create(goal));
    }

    private SkillLoader emptySkillLoader() {
        try {
            Path root = Files.createDirectory(
                    temporaryDirectory.resolve("skills-" + System.nanoTime()));
            return new SkillLoader(root.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private LlmResponse response(String content) {
        return new LlmResponse(LlmMessage.assistant(content, List.of()), "stop");
    }

    private record Fixture(
            AgentLoop loop,
            LlmClient client,
            ToolExecutor executor,
            TaskState task) {
    }
}
