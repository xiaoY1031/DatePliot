package com.example.harness.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

    /** Spring injects every AgentTool bean and registers it during application startup. */
    public ToolRegistry(List<AgentTool> agentTools) {
        agentTools.forEach(this::register);
    }

    public void register(AgentTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("不能注册空工具");
        }
        ToolDefinition definition = tool.definition();
        if (definition == null) {
            throw new IllegalArgumentException("工具定义不能为空：" + tool.getClass().getName());
        }

        AgentTool existing = tools.putIfAbsent(definition.name(), tool);
        if (existing != null) {
            throw new IllegalStateException("重复的工具名称：" + definition.name());
        }
    }

    public Optional<AgentTool> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(tools.get(name));
    }

    public List<AgentTool> listTools() {
        return tools.values().stream()
                .sorted((left, right) -> left.definition().name()
                        .compareTo(right.definition().name()))
                .toList();
    }

    public List<ToolDefinition> listDefinitions() {
        return listTools().stream()
                .map(AgentTool::definition)
                .toList();
    }
}

