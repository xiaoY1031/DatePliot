package com.example.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** One model-requested tool call after its arguments have been parsed. */
public record ToolInvocation(
        String toolCallId,
        String name,
        JsonNode arguments) {
}
