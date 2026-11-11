package com.example.harness.llm.model;

import com.example.harness.tool.ToolDefinition;

import java.util.List;

/** 单次模型请求，包含完整消息历史和当前可用工具定义。 */
public record LlmRequest(
        List<LlmMessage> messages,
        List<ToolDefinition> toolDefinitions) {

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        toolDefinitions = toolDefinitions == null ? List.of() : List.copyOf(toolDefinitions);
    }
}
