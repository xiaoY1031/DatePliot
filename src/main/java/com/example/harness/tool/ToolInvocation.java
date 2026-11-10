package com.example.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** 模型发起且参数已经解析的单次工具调用。 */
public record ToolInvocation(
        String toolCallId,
        String name,
        JsonNode arguments) {
}
