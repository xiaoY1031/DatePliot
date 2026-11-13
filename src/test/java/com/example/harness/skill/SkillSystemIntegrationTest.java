package com.example.harness.skill;

import com.example.harness.agent.AgentLoop;
import com.example.harness.context.ContextManager;
import com.example.harness.llm.LlmClient;
import com.example.harness.llm.model.LlmMessage;
import com.example.harness.llm.model.LlmRequest;
import com.example.harness.llm.model.LlmResponse;
import com.example.harness.llm.model.LlmToolCall;
import com.example.harness.task.TaskState;
import com.example.harness.task.TaskStore;
import com.example.harness.tool.ToolExecutor;
import com.example.harness.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillSystemIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadedSkillFlowsThroughToolResultIntoHistoryAndRemainsInNextTurn() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("skills"));
        Path skillDirectory = Files.createDirectory(root.resolve("data-analysis"));
        Files.writeString(skillDirectory.resolve("SKILL.md"), """
                ---
                name: data-analysis
                description: Analyze supplied data using evidence.
                ---
                # ANALYSIS_RULES
                Check data quality and do not invent results.
                """);

        SkillLoader skillLoader = new SkillLoader(root.toString());
        LoadSkillTool loadSkillTool = new LoadSkillTool(skillLoader);
        ToolRegistry registry = new ToolRegistry(List.of(loadSkillTool));
        TaskStore taskStore = new TaskStore();
        ContextManager contextManager = new ContextManager(
                taskStore,
                skillLoader,
                new ByteArrayResource("Base system prompt".getBytes(StandardCharsets.UTF_8)));
        LlmClient llmClient = mock(LlmClient.class);
        AgentLoop agentLoop = new AgentLoop(
                llmClient,
                new ToolExecutor(registry, new ObjectMapper()),
                registry,
                contextManager);
        TaskState task = taskStore.create("分析已有销售数据");
        AtomicInteger requests = new AtomicInteger();

        when(llmClient.complete(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            return switch (requests.getAndIncrement()) {
                case 0 -> {
                    assertTrue(request.messages().get(1).content().contains("data-analysis"));
                    assertTrue(request.messages().get(1).content().contains("Analyze supplied data"));
                    assertTrue(!request.messages().get(1).content().contains("ANALYSIS_RULES"));
                    assertEquals("load_skill", request.toolDefinitions().get(0).name());
                    yield new LlmResponse(LlmMessage.assistant(null, List.of(
                            new LlmToolCall("call-load-skill", "function", "load_skill",
                                    "{\"name\":\"data-analysis\"}"))), "tool_calls");
                }
                case 1 -> {
                    assertEquals("call-load-skill", request.messages().get(4).toolCalls().get(0).id());
                    assertEquals(LlmMessage.Role.TOOL, request.messages().get(5).role());
                    assertEquals("call-load-skill", request.messages().get(5).toolCallId());
                    assertTrue(request.messages().get(5).content().contains("ANALYSIS_RULES"));
                    yield response("已按分析流程检查数据。");
                }
                case 2 -> {
                    assertTrue(request.messages().stream()
                            .anyMatch(message -> message.content() != null
                                    && message.content().contains("ANALYSIS_RULES")));
                    assertTrue(request.messages().stream()
                            .anyMatch(message -> message.role() == LlmMessage.Role.USER
                                    && "继续解释刚才的发现".equals(message.content())));
                    yield response("继续分析结论。");
                }
                default -> throw new AssertionError("出现了意外的模型请求");
            };
        });

        assertEquals("已按分析流程检查数据。", agentLoop.run(task, "请分析这些数据"));
        assertEquals("继续分析结论。", agentLoop.run(task, "继续解释刚才的发现"));
        assertEquals(2, task.turnCount());
        assertEquals(6, task.conversationHistory().size());
        assertEquals(LlmMessage.Role.TOOL, task.conversationHistory().get(2).role());
        assertTrue(task.conversationHistory().get(2).content().contains("ANALYSIS_RULES"));
    }

    private LlmResponse response(String content) {
        return new LlmResponse(LlmMessage.assistant(content, List.of()), "stop");
    }
}
