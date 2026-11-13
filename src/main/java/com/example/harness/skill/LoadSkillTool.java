package com.example.harness.skill;

import com.example.harness.tool.AgentTool;
import com.example.harness.tool.ToolDefinition;
import com.example.harness.tool.ToolInvocation;
import com.example.harness.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 供模型按名称加载单个 Skill 的完整指令。 */
@Component
public class LoadSkillTool implements AgentTool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "load_skill",
            "按 Skill 名称加载其完整工作方法和质量要求。只加载当前任务需要的 Skill；如果对话历史已包含该 Skill 的完整指令，不要重复加载。",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "name", Map.of(
                                    "type", "string",
                                    "description", "Skill Catalog 中列出的 Skill 名称")),
                    "required", List.of("name"),
                    "additionalProperties", false));

    private final SkillLoader skillLoader;

    public LoadSkillTool(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        JsonNode arguments = invocation == null ? null : invocation.arguments();
        if (arguments == null || !arguments.isObject()
                || !arguments.path("name").isTextual()
                || arguments.path("name").asText().isBlank()) {
            return ToolResult.failure("INVALID_ARGUMENTS", "必须提供非空的 Skill name 字符串");
        }

        String name = arguments.path("name").asText();
        try {
            LoadedSkill loaded = skillLoader.load(name);
            return ToolResult.success("已加载 Skill：" + loaded.metadata().name()
                    + "\n\n" + loaded.instructions());
        } catch (SkillLoader.SkillNotFoundException e) {
            return ToolResult.failure("UNKNOWN_SKILL", e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("INVALID_ARGUMENTS", e.getMessage());
        }
    }
}
