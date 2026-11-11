package com.example.harness.llm.model;

/** 单次模型响应，保留 assistant 消息及模型结束原因。 */
public record LlmResponse(
        LlmMessage assistantMessage,
        String finishReason) {

    public LlmResponse {
        if (assistantMessage == null) {
            throw new IllegalArgumentException("模型响应消息不能为空");
        }
    }
}
