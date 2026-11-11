package com.example.harness.llm;

import com.example.harness.llm.model.LlmMessage;
import com.example.harness.llm.model.LlmRequest;
import com.example.harness.llm.model.LlmResponse;
import com.example.harness.llm.model.LlmToolCall;
import com.example.harness.tool.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 千问兼容模式客户端，只负责协议转换、HTTP 通信和响应解析。 */
@Component
public class QwenClient implements LlmClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public QwenClient(
            @Value("${qwen.api-key}") String apiKey,
            @Value("${qwen.base-url}") String baseUrl,
            @Value("${qwen.model}") String model,
            RestClient.Builder builder) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("模型请求不能为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("请配置 DASHSCOPE_API_KEY");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", request.messages().stream().map(this::toQwenMessage).toList());
        requestBody.put("tools", request.toolDefinitions().stream().map(this::toFunctionTool).toList());
        requestBody.put("tool_choice", "auto");

        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);
        return parseResponse(response);
    }

    private Map<String, Object> toQwenMessage(LlmMessage message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", message.role().apiValue());
        result.put("content", message.content());
        if (!message.toolCalls().isEmpty()) {
            result.put("tool_calls", message.toolCalls().stream()
                    .map(this::toQwenToolCall)
                    .toList());
        }
        if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
            result.put("tool_call_id", message.toolCallId());
        }
        return result;
    }

    private Map<String, Object> toQwenToolCall(LlmToolCall toolCall) {
        return Map.of(
                "id", toolCall.id(),
                "type", toolCall.type(),
                "function", Map.of(
                        "name", toolCall.name(),
                        "arguments", toolCall.arguments()));
    }

    private Map<String, Object> toFunctionTool(ToolDefinition definition) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", definition.name(),
                        "description", definition.description(),
                        "parameters", definition.parameters()));
    }

    private LlmResponse parseResponse(JsonNode response) {
        if (response == null || !response.path("choices").isArray()
                || response.path("choices").isEmpty()) {
            throw new IllegalStateException("千问 API 没有返回有效回答");
        }

        JsonNode choice = response.path("choices").get(0);
        JsonNode message = choice.path("message");
        if (!message.isObject()) {
            throw new IllegalStateException("千问 API 返回的 message 格式无效");
        }

        String content = parseContent(message.path("content"));
        List<LlmToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        String finishReason = choice.path("finish_reason").isTextual()
                ? choice.path("finish_reason").asText()
                : null;
        return new LlmResponse(LlmMessage.assistant(content, toolCalls), finishReason);
    }

    private String parseContent(JsonNode content) {
        if (content.isMissingNode() || content.isNull()) {
            return null;
        }
        if (!content.isTextual()) {
            throw new IllegalStateException("千问 API 返回的 content 格式无效");
        }
        return content.asText();
    }

    private List<LlmToolCall> parseToolCalls(JsonNode toolCalls) {
        if (toolCalls.isMissingNode() || toolCalls.isNull()) {
            return List.of();
        }
        if (!toolCalls.isArray()) {
            throw new IllegalStateException("千问返回的 tool_calls 格式无效");
        }

        List<LlmToolCall> result = new ArrayList<>();
        for (JsonNode toolCall : toolCalls) {
            JsonNode function = toolCall.path("function");
            String id = requiredText(toolCall.path("id"), "千问返回的工具调用缺少 ID");
            String type = requiredText(toolCall.path("type"), "千问返回的工具调用缺少类型");
            if (!"function".equals(type)) {
                throw new IllegalStateException("千问返回的工具调用类型无效");
            }
            if (!function.isObject()) {
                throw new IllegalStateException("千问返回的工具函数格式无效");
            }
            String name = optionalText(function.path("name"));
            String arguments = optionalText(function.path("arguments"));
            result.add(new LlmToolCall(id, type, name, arguments));
        }
        return List.copyOf(result);
    }

    private String requiredText(JsonNode node, String errorMessage) {
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new IllegalStateException(errorMessage);
        }
        return node.asText();
    }

    /** 工具名称或参数缺失时保留为空字符串，由 ToolExecutor 生成可回传模型的工具错误。 */
    private String optionalText(JsonNode node) {
        return node.isTextual() ? node.asText() : "";
    }
}
