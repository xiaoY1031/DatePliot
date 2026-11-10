package com.example.harness.tool;

/** 统一的智能体工具契约，工具负责提供自身定义和执行逻辑。 */
public interface AgentTool {

    ToolDefinition definition();

    ToolResult execute(ToolInvocation invocation);
}
