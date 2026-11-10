package com.example.harness.tool;

/** A tool that can describe and execute itself through the common agent tool contract. */
public interface AgentTool {

    ToolDefinition definition();

    ToolResult execute(ToolInvocation invocation);
}
