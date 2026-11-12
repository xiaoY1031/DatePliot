package com.example.harness.llm;

import com.example.harness.llm.model.LlmMessage;
import com.example.harness.llm.model.LlmRequest;
import com.example.harness.llm.model.LlmToolCall;
import com.example.harness.tool.ListFilesTool;
import com.example.harness.tool.ProjectInfoTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QwenClientTest {

    @Test
    void sendsStructuredRequestAndParsesOrdinaryAnswer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenClient client = createClient(builder);

        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("qwen-plus"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("系统提示"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("你好"))
                .andExpect(jsonPath("$.tools[0].function.name").value("get_project_info"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop",
                        "message":{"role":"assistant","content":"你好"}}]}
                        """, MediaType.APPLICATION_JSON));

        var response = client.complete(new LlmRequest(
                List.of(LlmMessage.system("系统提示"), LlmMessage.user("你好")),
                List.of(new ProjectInfoTool().definition())));

        assertEquals("你好", response.assistantMessage().content());
        assertEquals("stop", response.finishReason());
        assertEquals(List.of(), response.assistantMessage().toolCalls());
        server.verify();
    }

    @Test
    void parsesToolCallsAndPreservesAssistantHistory() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenClient client = createClient(builder);

        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].id").value("call_1"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].function.arguments")
                        .value("{\"path\":\"src\"}"))
                .andExpect(jsonPath("$.messages[2].role").value("tool"))
                .andExpect(jsonPath("$.messages[2].tool_call_id").value("call_1"))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"tool_calls","message":{
                        "role":"assistant","content":null,"tool_calls":[{
                        "id":"call_2","type":"function","function":{
                        "name":"list_files","arguments":"{\\\"path\\\":\\\"src/main\\\"}"}}]}}]}
                        """, MediaType.APPLICATION_JSON));

        var previousCall = new LlmToolCall(
                "call_1", "function", "list_files", "{\"path\":\"src\"}");
        var response = client.complete(new LlmRequest(
                List.of(
                        LlmMessage.user("查看目录"),
                        LlmMessage.assistant(null, List.of(previousCall)),
                        LlmMessage.tool("call_1", "[目录] main")),
                List.of()));

        assertNull(response.assistantMessage().content());
        assertEquals("tool_calls", response.finishReason());
        assertEquals("call_2", response.assistantMessage().toolCalls().get(0).id());
        assertEquals("list_files", response.assistantMessage().toolCalls().get(0).name());
        assertEquals("{\"path\":\"src/main\"}",
                response.assistantMessage().toolCalls().get(0).arguments());
        server.verify();
    }

    @Test
    void sendsParameterSchemaProvidedByRegistryTool(@TempDir Path workspace) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenClient client = createClient(builder);

        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.tools.length()").value(1))
                .andExpect(jsonPath("$.tools[0].function.name").value("list_files"))
                .andExpect(jsonPath("$.tools[0].function.parameters.properties.path.type")
                        .value("string"))
                .andExpect(jsonPath("$.tools[0].function.parameters.required[0]")
                        .value("path"))
                .andExpect(jsonPath("$.tools[0].function.parameters.additionalProperties")
                        .value(false))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                        """, MediaType.APPLICATION_JSON));

        client.complete(new LlmRequest(
                List.of(LlmMessage.user("查看目录")),
                List.of(new ListFilesTool(workspace.toString()).definition())));
        server.verify();
    }

    @Test
    void rejectsInvalidQwenResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenClient client = createClient(builder);

        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        var error = assertThrows(IllegalStateException.class,
                () -> client.complete(new LlmRequest(List.of(), List.of())));
        assertEquals("千问 API 没有返回有效回答", error.getMessage());
        server.verify();
    }

    @Test
    void rejectsToolCallWithoutId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenClient client = createClient(builder);

        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":null,
                        "tool_calls":[{"type":"function","function":{
                        "name":"get_project_info","arguments":"{}"}}]}}]}
                        """, MediaType.APPLICATION_JSON));

        var error = assertThrows(IllegalStateException.class,
                () -> client.complete(new LlmRequest(List.of(), List.of())));
        assertEquals("千问返回的工具调用缺少 ID", error.getMessage());
        server.verify();
    }

    @Test
    void preservesMissingArgumentsForExecutorValidation() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenClient client = createClient(builder);

        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call_1","type":"function","function":{
                        "name":"get_project_info"}}]}}]}
                        """, MediaType.APPLICATION_JSON));

        var response = client.complete(new LlmRequest(List.of(), List.of()));

        assertEquals("", response.assistantMessage().toolCalls().get(0).arguments());
        server.verify();
    }

    private QwenClient createClient(RestClient.Builder builder) {
        return new QwenClient(
                "test-key",
                "https://example.test/v1",
                "qwen-plus",
                builder);
    }
}
