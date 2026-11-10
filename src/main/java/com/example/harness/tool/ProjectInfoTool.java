package com.example.harness.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProjectInfoTool implements AgentTool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "get_project_info",
            "查询 CampusX 项目的基本信息和技术栈",
            Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", List.of(),
                    "additionalProperties", false));

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        return ToolResult.success(getProjectInfo());
    }

    /** 返回代码中固定的 CampusX 项目信息，不读取数据库或项目文件。 */
    public String getProjectInfo() {
        return """
                项目名称：CampusX
                技术栈：Spring Boot、MySQL、Redis
                项目类型：校园生活服务平台
                """;
    }
}
