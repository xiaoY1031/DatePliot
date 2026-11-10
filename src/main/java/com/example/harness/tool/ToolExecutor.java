package com.example.harness.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public ToolExecutor(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    /** Compatibility entry point for existing no-argument callers. */
    public String execute(String toolName) {
        return execute(null, toolName, "{}").toModelContent();
    }

    /** Parses raw model arguments without allowing malformed calls to abort the agent loop. */
    public ToolResult execute(String toolCallId, String toolName, String rawArguments) {
        AgentTool tool = toolRegistry.find(toolName).orElse(null);
        if (tool == null) {
            return ToolResult.failure("UNKNOWN_TOOL", "不支持的工具：" + toolName);
        }

        JsonNode arguments;
        try {
            arguments = objectMapper.readTree(rawArguments);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return ToolResult.failure("INVALID_ARGUMENTS", "参数不是有效 JSON");
        }

        return execute(tool, new ToolInvocation(toolCallId, toolName, arguments));
    }

    public ToolResult execute(ToolInvocation invocation) {
        if (invocation == null) {
            return ToolResult.failure("INVALID_INVOCATION", "工具调用不能为空");
        }

        AgentTool tool = toolRegistry.find(invocation.name()).orElse(null);
        if (tool == null) {
            return ToolResult.failure("UNKNOWN_TOOL", "不支持的工具：" + invocation.name());
        }
        return execute(tool, invocation);
    }

    private ToolResult execute(AgentTool tool, ToolInvocation invocation) {
        String validationError = validateArguments(tool.definition(), invocation.arguments());
        if (validationError != null) {
            return ToolResult.failure("INVALID_ARGUMENTS", validationError);
        }

        try {
            ToolResult result = tool.execute(invocation);
            return result == null
                    ? ToolResult.failure("EXECUTION_ERROR", "工具没有返回执行结果")
                    : result;
        } catch (RuntimeException e) {
            return ToolResult.failure("EXECUTION_ERROR", "工具执行失败：" + e.getMessage());
        }
    }

    /** Minimal validation for the object schemas currently supported by local tools. */
    private String validateArguments(ToolDefinition definition, JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            return definition.name() + " 的参数必须是 JSON 对象";
        }

        Map<String, Object> schema = definition.parameters();
        Object requiredValue = schema.get("required");
        if (requiredValue instanceof List<?> required) {
            for (Object name : required) {
                if (name instanceof String requiredName && !arguments.has(requiredName)) {
                    return "缺少必填参数：" + requiredName;
                }
            }
        }

        Map<?, ?> properties = schema.get("properties") instanceof Map<?, ?> value
                ? value
                : Map.of();
        if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            var fieldNames = arguments.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (!properties.containsKey(fieldName)) {
                    return "不支持的参数：" + fieldName;
                }
            }
        }

        var fields = arguments.properties().iterator();
        while (fields.hasNext()) {
            var field = fields.next();
            Object propertySchema = properties.get(field.getKey());
            if (propertySchema instanceof Map<?, ?> propertyMap
                    && propertyMap.get("type") instanceof String expectedType
                    && !matchesType(field.getValue(), expectedType)) {
                return "参数 " + field.getKey() + " 的类型必须是 " + expectedType;
            }
        }
        return null;
    }

    private boolean matchesType(JsonNode value, String expectedType) {
        return switch (expectedType) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> true;
        };
    }
}
