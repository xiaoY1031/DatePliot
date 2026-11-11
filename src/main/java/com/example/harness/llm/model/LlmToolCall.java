package com.example.harness.llm.model;

/** 模型发起的结构化工具调用，保留千问返回的调用 ID 和原始参数。 */
public record LlmToolCall(
        String id,
        String type,
        String name,
        String arguments) {

    public LlmToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("工具调用 ID 不能为空");
        }
        if (!"function".equals(type)) {
            throw new IllegalArgumentException("工具调用类型必须是 function");
        }
        name = name == null ? "" : name;
        arguments = arguments == null ? "" : arguments;
    }
}
