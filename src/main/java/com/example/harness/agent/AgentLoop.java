package com.example.harness.agent;

import com.example.harness.llm.LlmClient;
import com.example.harness.llm.model.LlmMessage;
import com.example.harness.llm.model.LlmRequest;
import com.example.harness.llm.model.LlmResponse;
import com.example.harness.llm.model.LlmToolCall;
import com.example.harness.tool.ToolExecutor;
import com.example.harness.tool.ToolRegistry;
import com.example.harness.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;

@Component
public class AgentLoop {

    private static final int MAX_MODEL_REQUESTS = 10;

    private final LlmClient llmClient;
    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;

    /** 由 Spring 注入模型客户端、工具执行器和注册表。 */
    public AgentLoop(LlmClient llmClient, ToolExecutor toolExecutor, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 维护单次请求的完整消息历史，最多请求模型十次。
     * 有工具调用时保存 assistant 消息、执行全部工具并追加对应结果；否则返回最终正文。
     */
    public String run(String question) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.user(question));
        var toolDefinitions = toolRegistry.listDefinitions();

        for (int round = 0; round < MAX_MODEL_REQUESTS; round++) {
            LlmResponse response = llmClient.complete(new LlmRequest(messages, toolDefinitions));
            LlmMessage assistantMessage = response.assistantMessage();
            List<LlmToolCall> toolCalls = assistantMessage.toolCalls();

            if (toolCalls.isEmpty()) {
                if (assistantMessage.content() == null || assistantMessage.content().isBlank()) {
                    throw new IllegalStateException("千问 API 没有返回有效回答");
                }
                return assistantMessage.content();
            }

            messages.add(assistantMessage);
            for (LlmToolCall toolCall : toolCalls) {
                ToolResult result = toolExecutor.execute(
                        toolCall.id(),
                        toolCall.name(),
                        toolCall.arguments());
                messages.add(LlmMessage.tool(toolCall.id(), result.toModelContent()));
            }
        }

        throw new IllegalStateException("Agent 超过最大循环次数 10 轮");
    }
}

