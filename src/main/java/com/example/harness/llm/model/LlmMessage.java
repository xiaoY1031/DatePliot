package com.example.harness.llm.model;

import java.util.List;

/** Agent Loop 内部使用的统一消息对象。 */
public record LlmMessage(
        Role role,
        String content,
        List<LlmToolCall> toolCalls,
        String toolCallId) {

    public LlmMessage {
        if (role == null) {
            throw new IllegalArgumentException("消息角色不能为空");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content, List.of(), null);
    }

    public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage(Role.ASSISTANT, content, toolCalls, null);
    }

    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage(Role.TOOL, content, List.of(), toolCallId);
    }

    public enum Role {
        USER("user"),
        ASSISTANT("assistant"),
        TOOL("tool");

        private final String apiValue;

        Role(String apiValue) {
            this.apiValue = apiValue;
        }

        public String apiValue() {
            return apiValue;
        }
    }
}
