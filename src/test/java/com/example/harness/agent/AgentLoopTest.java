package com.example.harness.agent;

import com.example.harness.llm.LlmClient;
import com.example.harness.llm.model.LlmMessage;
import com.example.harness.llm.model.LlmRequest;
import com.example.harness.llm.model.LlmResponse;
import com.example.harness.llm.model.LlmToolCall;
import com.example.harness.tool.ProjectInfoTool;
import com.example.harness.tool.ToolExecutor;
import com.example.harness.tool.ToolRegistry;
import com.example.harness.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLoopTest {

    @Test
    void returnsOrdinaryAnswerWithoutCallingTool() {
        LlmClient client = mock(LlmClient.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        when(client.complete(any())).thenReturn(response("你好"));

        String answer = createLoop(client, executor).run("你好");

        assertEquals("你好", answer);
        verify(client).complete(any());
        verify(executor, times(0)).execute(anyString(), anyString(), anyString());
    }

    @Test
    void executesMultipleToolCallsWithArgumentsAndMatchingIds() {
        LlmClient client = mock(LlmClient.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        AtomicInteger requests = new AtomicInteger();
        when(client.complete(any())).thenAnswer(invocation -> {
            if (requests.getAndIncrement() == 0) {
                return new LlmResponse(LlmMessage.assistant(null, List.of(
                        new LlmToolCall("call_1", "function", "get_project_info", "{}"),
                        new LlmToolCall("call_2", "function", "list_files", "{\"path\":\"src\"}"))),
                        "tool_calls");
            }
            LlmRequest request = invocation.getArgument(0);
            assertEquals(4, request.messages().size());
            assertEquals("call_1", request.messages().get(2).toolCallId());
            assertEquals("项目结果", request.messages().get(2).content());
            assertEquals("call_2", request.messages().get(3).toolCallId());
            assertEquals("目录结果", request.messages().get(3).content());
            return response("最终回答");
        });
        when(executor.execute("call_1", "get_project_info", "{}"))
                .thenReturn(ToolResult.success("项目结果"));
        when(executor.execute("call_2", "list_files", "{\"path\":\"src\"}"))
                .thenReturn(ToolResult.success("目录结果"));

        assertEquals("最终回答", createLoop(client, executor).run("查看项目和目录"));
        verify(executor).execute("call_1", "get_project_info", "{}");
        verify(executor).execute("call_2", "list_files", "{\"path\":\"src\"}");
    }

    @Test
    void returnsToolErrorsToModelAndContinues() {
        LlmClient client = mock(LlmClient.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        when(client.complete(any()))
                .thenReturn(new LlmResponse(LlmMessage.assistant(null, List.of(
                        new LlmToolCall("call_unknown", "function", "missing_tool", "{}"))),
                        "tool_calls"))
                .thenAnswer(invocation -> {
                    LlmRequest request = invocation.getArgument(0);
                    assertThat(request.messages().get(2).content(), containsString("UNKNOWN_TOOL"));
                    return response("已处理工具错误。");
                });
        when(executor.execute("call_unknown", "missing_tool", "{}"))
                .thenReturn(ToolResult.failure("UNKNOWN_TOOL", "不支持的工具：missing_tool"));

        assertEquals("已处理工具错误。", createLoop(client, executor).run("测试未知工具"));
    }

    @Test
    void returnsInvalidArgumentsToModelAndContinues() {
        LlmClient client = mock(LlmClient.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        when(client.complete(any()))
                .thenReturn(new LlmResponse(LlmMessage.assistant(null, List.of(
                        new LlmToolCall("call_invalid", "function", "get_project_info", ""))),
                        "tool_calls"))
                .thenAnswer(invocation -> {
                    LlmRequest request = invocation.getArgument(0);
                    assertThat(request.messages().get(2).content(), containsString("INVALID_ARGUMENTS"));
                    return response("已处理参数错误。");
                });
        when(executor.execute("call_invalid", "get_project_info", ""))
                .thenReturn(ToolResult.failure("INVALID_ARGUMENTS", "参数不是有效 JSON"));

        assertEquals("已处理参数错误。", createLoop(client, executor).run("测试非法参数"));
    }

    @Test
    void exposesRegistryDefinitionsOnEveryModelRequest() {
        LlmClient client = mock(LlmClient.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        when(client.complete(any())).thenReturn(response("完成"));

        createLoop(client, executor).run("项目介绍");

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(client).complete(captor.capture());
        assertEquals("get_project_info", captor.getValue().toolDefinitions().get(0).name());
    }

    @Test
    void rejectsBlankFinalAnswer() {
        LlmClient client = mock(LlmClient.class);
        when(client.complete(any())).thenReturn(response(" "));

        var error = assertThrows(IllegalStateException.class,
                () -> createLoop(client, mock(ToolExecutor.class)).run("你好"));
        assertEquals("千问 API 没有返回有效回答", error.getMessage());
    }

    @Test
    void stopsAfterTenModelRequests() {
        LlmClient client = mock(LlmClient.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        when(client.complete(any())).thenReturn(new LlmResponse(
                LlmMessage.assistant(null, List.of(
                        new LlmToolCall("call_1", "function", "get_project_info", "{}"))),
                "tool_calls"));
        when(executor.execute("call_1", "get_project_info", "{}"))
                .thenReturn(ToolResult.success("项目信息"));

        var error = assertThrows(IllegalStateException.class,
                () -> createLoop(client, executor).run("项目介绍"));

        assertEquals("Agent 超过最大循环次数 10 轮", error.getMessage());
        verify(client, times(10)).complete(any());
        verify(executor, times(10)).execute("call_1", "get_project_info", "{}");
    }

    private AgentLoop createLoop(LlmClient client, ToolExecutor executor) {
        return new AgentLoop(
                client,
                executor,
                new ToolRegistry(List.of(new ProjectInfoTool())));
    }

    private LlmResponse response(String content) {
        return new LlmResponse(LlmMessage.assistant(content, List.of()), "stop");
    }
}
