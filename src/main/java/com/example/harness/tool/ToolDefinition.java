package com.example.harness.tool;

import java.util.Map;

/** Tool metadata exposed to the model. */
public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters) {

    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("工具描述不能为空：" + name);
        }
        if (parameters == null || !"object".equals(parameters.get("type"))) {
            throw new IllegalArgumentException("工具参数 Schema 必须描述 object：" + name);
        }
        parameters = Map.copyOf(parameters);
    }
}
