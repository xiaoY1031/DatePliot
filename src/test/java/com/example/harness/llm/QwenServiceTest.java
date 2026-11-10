package com.example.harness.llm;

import com.example.harness.tool.AgentTool;
import com.example.harness.tool.ProjectInfoTool;
import com.example.harness.tool.ToolDefinition;
import com.example.harness.tool.ToolExecutor;
import com.example.harness.tool.ToolInvocation;
import com.example.harness.tool.ToolRegistry;
import com.example.harness.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QwenServiceTest {
    /** 验证模型持续请求工具时恰好请求十轮，并抛出明确的循环上限异常。 */
    @Test
    void stopsAgentAfterTenModelRequests() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenService service = createService(builder);
        for (int round = 0; round < 10; round++) {
            server.expect(requestTo("https://example.test/v1/chat/completions"))
                    .andExpect(jsonPath("$.tools[0].function.name").value("get_project_info"))
                    .andExpect(jsonPath("$.tool_choice").value("auto"))
                    .andRespond(withSuccess("""
                            {"choices":[{"message":{"role":"assistant","content":null,
                            "tool_calls":[{"id":"call_1","type":"function","function":{
                            "name":"get_project_info","arguments":"{}"}}]}}]}
                            """, MediaType.APPLICATION_JSON));
        }
        var error = assertThrows(IllegalStateException.class, () -> service.runAgent("介绍项目"));
        assertEquals("Agent 超过最大循环次数 10 轮", error.getMessage());
        server.verify();
    }

    /** 验证请求携带工具说明，返回包含响应 ID 和 choices 的完整 JSON。 */
    @Test
    void returnsFullResponseForFinalAnswer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenService service = createService(builder);
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.tools[0].function.name").value("get_project_info"))
                .andExpect(jsonPath("$.messages[0].tool_call_id").value("call_1"))
                .andRespond(withSuccess("""
                        {"id":"response_1","choices":[{"message":{"role":"assistant","content":"CampusX 使用 Redis。"}}]}
                        """, MediaType.APPLICATION_JSON));
        var response = service.getFinalAnswer(java.util.List.of(
                java.util.Map.of("role", "tool", "tool_call_id", "call_1", "content", "Redis")));
        assertEquals("response_1", response.path("id").asText());
        assertEquals("CampusX 使用 Redis。", response.path("choices").get(0)
                .path("message").path("content").asText());
        server.verify();
    }

    /** 验证正文为 null 时仍保留工具调用信息，供后续循环解析，不执行工具。 */
    @Test
    void preservesToolCallsInFullResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenService service = createService(builder);
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call_1","type":"function","function":{
                        "name":"get_project_info","arguments":"{}"}}]}}]}
                        """, MediaType.APPLICATION_JSON));
        var response = service.getFinalAnswer(java.util.List.of());
        assertEquals("get_project_info", response.path("choices").get(0).path("message")
                .path("tool_calls").get(0).path("function").path("name").asText());
        server.verify();
    }

    /** 模拟工具调用及最终回答，验证保留 assistant 消息并携带匹配 ID 回传工具结果。 */
    @Test
    void executesToolAndReturnsModelFinalAnswer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ToolExecutor tool = org.mockito.Mockito.mock(ToolExecutor.class);
        org.mockito.Mockito.when(tool.execute("call_1", "get_project_info", "{}"))
                .thenReturn(ToolResult.success("CampusX 项目信息"));
        QwenService service = createService(builder, tool, projectInfoRegistry());
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andExpect(jsonPath("$.tools[0].function.parameters.additionalProperties").value(false))
                .andRespond(withSuccess("""
                        {"choices":[{"index":0,"finish_reason":"tool_calls",
                        "message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call_1","type":"function","function":{
                        "name":"get_project_info","arguments":"{}"}}]}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[0].content").value("查询 CampusX 的技术栈"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].id").value("call_1"))
                .andExpect(jsonPath("$.messages[2].role").value("tool"))
                .andExpect(jsonPath("$.messages[2].tool_call_id").value("call_1"))
                .andExpect(jsonPath("$.messages[2].content").value("CampusX 项目信息"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"这是模型整理后的项目信息。"}}]}
                        """, MediaType.APPLICATION_JSON));
        assertEquals("这是模型整理后的项目信息。", service.chatWithTools("查询 CampusX 的技术栈"));
        org.mockito.Mockito.verify(tool).execute("call_1", "get_project_info", "{}");
        org.mockito.Mockito.verifyNoMoreInteractions(tool);
        server.verify();
    }

    @Test
    /** 模拟两次模型响应，验证多轮 chat 回传工具结果时保留对应的 tool_call_id。 */
    void executesToolAndSendsResultBackWithMatchingId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenService service = createService(builder);

        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.tools[0].function.name").value("get_project_info"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call_1","type":"function","function":{
                        "name":"get_project_info","arguments":"{}"}}]}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].id").value("call_1"))
                .andExpect(jsonPath("$.messages[2].role").value("tool"))
                .andExpect(jsonPath("$.messages[2].tool_call_id").value("call_1"))
                .andExpect(jsonPath("$.messages[2].content").value(new ProjectInfoTool().getProjectInfo()))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"CampusX 使用 Spring Boot、MySQL、Redis。"}}]}
                        """, MediaType.APPLICATION_JSON));
        assertEquals("CampusX 使用 Spring Boot、MySQL、Redis。", service.chat("项目用了哪些技术？"));
        server.verify();
    }

    @Test
    /** 模拟普通文本响应，验证 chatWithTools 无工具调用时直接返回回答。 */
    void returnsOrdinaryAnswerWithoutCallingTool() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenService service = createService(builder);
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"你好"}}]}
                        """, MediaType.APPLICATION_JSON));
        assertEquals("你好", service.chatWithTools("你好"));
        server.verify();
    }

    @Test
    void exposesDefinitionsFromRegistry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentTool registryOnlyTool = new AgentTool() {
            private final ToolDefinition definition = new ToolDefinition(
                    "registry_only_tool",
                    "仅用于验证 Registry 动态暴露工具",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(),
                            "required", List.of(),
                            "additionalProperties", false));

            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                return ToolResult.success("ok");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(registryOnlyTool));
        ObjectMapper mapper = new ObjectMapper();
        QwenService service = createService(
                builder, new ToolExecutor(registry, mapper), registry, mapper);

        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.tools.length()").value(1))
                .andExpect(jsonPath("$.tools[0].function.name").value("registry_only_tool"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                        """, MediaType.APPLICATION_JSON));

        service.getFinalAnswer(List.of());
        server.verify();
    }

    @Test
    void returnsToolErrorsToModelAndContinuesAgentLoop() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenService service = createService(builder);

        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                        {"id":"call_unknown","type":"function","function":{
                        "name":"missing_tool","arguments":"{}"}},
                        {"id":"call_invalid","type":"function","function":{
                        "name":"get_project_info","arguments":"{\\"unexpected\\":true}"}}
                        ]}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[2].tool_call_id").value("call_unknown"))
                .andExpect(jsonPath("$.messages[2].content",
                        containsString("UNKNOWN_TOOL")))
                .andExpect(jsonPath("$.messages[3].tool_call_id").value("call_invalid"))
                .andExpect(jsonPath("$.messages[3].content",
                        containsString("INVALID_ARGUMENTS")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"已处理工具错误。"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertEquals("已处理工具错误。", service.runAgent("测试错误工具调用"));
        server.verify();
    }

    private QwenService createService(RestClient.Builder builder) {
        ToolRegistry registry = projectInfoRegistry();
        ObjectMapper mapper = new ObjectMapper();
        return createService(builder, new ToolExecutor(registry, mapper), registry, mapper);
    }

    private QwenService createService(
            RestClient.Builder builder,
            ToolExecutor executor,
            ToolRegistry registry) {
        return createService(builder, executor, registry, new ObjectMapper());
    }

    private QwenService createService(
            RestClient.Builder builder,
            ToolExecutor executor,
            ToolRegistry registry,
            ObjectMapper mapper) {
        return new QwenService(
                "test-key",
                "https://example.test/v1",
                "qwen-plus",
                executor,
                registry,
                mapper,
                builder);
    }

    private ToolRegistry projectInfoRegistry() {
        return new ToolRegistry(List.of(new ProjectInfoTool()));
    }
}
