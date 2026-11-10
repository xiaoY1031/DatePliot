package com.example.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.example.harness.tool.ToolDefinition;
import com.example.harness.tool.ToolExecutor;
import com.example.harness.tool.ToolRegistry;
import com.example.harness.tool.ToolResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@Service
public class QwenService implements LlmClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private static final int MAX_AGENT_ROUNDS = 10;

    //QwenService 的构造方法，负责初始化服务。Spring 启动时会自动提供这些参数并创建对象，不是每次用户提问都执行。
    public QwenService(
            @Value("${qwen.api-key}") String apiKey,
            @Value("${qwen.base-url}") String baseUrl,
            @Value("${qwen.model}") String model,
            ToolExecutor toolExecutor,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            RestClient.Builder builder) {

        this.apiKey = apiKey;
        this.model = model;
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.restClient = builder
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * 实现 LlmClient 的聊天入口，委托 runAgent 执行最多十轮的智能体流程。
     */
    @Override
    public String chat(String question) {
        return runAgent(question);
    }

    /**
     * 创建独立的可变对话记录，最多请求模型十轮，每轮都携带工具说明。
     * 无工具调用时返回回答；有调用时保存原始消息、执行全部工具并追加结果，继续下一轮。
     * 十轮内未得到最终回答则抛出异常，避免无限循环。
     */
    public String runAgent(String question) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", question));
        for (int round = 0; round < MAX_AGENT_ROUNDS; round++) {
            JsonNode response = getFinalAnswer(messages);
            JsonNode message = extractChoice(response)
                    .path("message");
            JsonNode toolCalls = getToolCalls(message);
            if (toolCalls.isEmpty()) {
                //从返回结果里取出回答
                JsonNode content = message.path("content");
                if (!content.isTextual() || content.asText().isBlank()) {
                    throw new IllegalStateException("千问 API 没有返回有效回答");
                }
                return content.asText();
            }

            // 保留模型的完整工具调用消息，再逐个追加对应的执行结果。
            messages.add(objectMapper.convertValue(message, new TypeReference<Map<String, Object>>() {}));
            for (JsonNode toolCall : toolCalls) {
                String id = toolCall.path("id").asText();
                String name = toolCall.path("function").path("name").asText();
                if (id.isBlank() || !"function".equals(toolCall.path("type").asText())) {
                    throw new IllegalStateException("千问返回的工具调用缺少 ID 或类型无效");
                }
                ToolResult result = toolExecutor.execute(
                        id,
                        name,
                        toolCall.path("function").path("arguments").asText(null));
                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", id,
                        "content", result.toModelContent()));
            }
        }
        throw new IllegalStateException("Agent 超过最大循环次数 10 轮");
    }

    /**
     * 接收用户问题；有工具调用时执行工具、追加结果，再请求模型生成最终回答。
     * 没有工具调用则直接返回模型文本；本阶段最多请求模型两次，不循环调用工具。
     */
    public String chatWithTools(String question) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", question));
        JsonNode choice = requestChoice(new ArrayList<>(messages), "auto");
        JsonNode message = choice.path("message");
        JsonNode toolCalls = getToolCalls(message);

        // 模型没有请求调用工具
        if (toolCalls.isEmpty()) {
            return message.path("content").asText();
        }

        // 保留模型原始的 assistant 消息，包含 tool_calls 和可能为 null 的 content。
        messages.add(objectMapper.convertValue(message, new TypeReference<Map<String, Object>>() {}));

        // 执行模型请求的工具；每个调用都必须追加对应结果，才能完整回传工具调用记录。
        for (JsonNode toolCall : toolCalls) {
            String toolCallId = toolCall.path("id").asText();
            String toolName = toolCall.path("function").path("name").asText();
            if (toolCallId.isBlank()) {
                throw new IllegalStateException("千问返回的工具调用缺少 ID");
            }
            ToolResult result = toolExecutor.execute(
                    toolCallId,
                    toolName,
                    toolCall.path("function").path("arguments").asText(null));
            messages.add(Map.of(
                    "role", "tool",
                    "tool_call_id", toolCallId,
                    "content", result.toModelContent()
            ));
        }

        // 把工具结果交回模型，由模型组织本次最终回答。
        JsonNode nextResponse = getFinalAnswer(messages);
        return extractAnswer(nextResponse);
    }

    /**
     * 将调用方准备好的对话记录和工具说明发送给模型，返回完整 JSON 响应。
     * 由调用方判断 content 和 tool_calls，不在本方法中提取最终答案。
     * 使用工具结果时，messages 应包含用户消息、原始 assistant 工具调用消息，
     * 以及带有对应 tool_call_id 的 tool 结果消息；本方法不自动组装这些记录。
     */
    public JsonNode getFinalAnswer(List<Map<String, Object>> messages) {
        //组装请求体
        Map<String, Object> requestBody = Map.of(
                "model", model,
                //对话记录
                "messages", messages,
                "tools", toolDefinitions(),
                "tool_choice", "auto"
        );

        //向大模型发送 HTTP 请求，模型根据工具结果组织回答
        JsonNode response = sendRequest(requestBody);

        //返回完整模型响应，供后续 Agent Loop 判断回答或工具调用。
        return response;
    }

    /**
     * 携带对话记录和工具说明请求模型，返回第一条完整 choice。
     * toolChoice 为 auto 时由模型决定是否调用工具，为 none 时禁止工具调用。
     */
    private JsonNode requestChoice(List<Object> messages, String toolChoice) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", messages,
                "tools", toolDefinitions(),
                "tool_choice", toolChoice
        );

        //真正发送 HTTP 请求，模型可能返回普通文本或工具调用；none 时不应提出工具调用。
        return extractChoice(sendRequest(requestBody));
    }

    /** 根据 Spring 中注册的工具生成模型所需的函数工具定义。 */
    private List<Map<String, Object>> toolDefinitions() {
        return toolRegistry.listDefinitions().stream()
                .map(this::toFunctionTool)
                .toList();
    }

    private Map<String, Object> toFunctionTool(ToolDefinition definition) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", definition.name(),
                        "description", definition.description(),
                        "parameters", definition.parameters()));
    }

    /**
     * 使用密钥向千问发送请求，返回完整 JSON 响应，不解析 choices 或回答正文。
     * HTTP 请求失败时由 RestClient 抛出异常；响应结构由解析方法检查。
     */
    private JsonNode sendRequest(Map<String, Object> requestBody) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("请配置 DASHSCOPE_API_KEY");
        }
        return restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

    }

    /** 检查完整响应是否包含 choice 和 message，返回第一条 choice；不要求包含回答正文。 */
    private JsonNode extractChoice(JsonNode response) {
        //检查响应是否包含 choice 和 message；这里不检查回答文本是否为空。
        if (response == null || !response.path("choices").isArray()
                || response.path("choices").isEmpty()) {
            throw new IllegalStateException(
                    "千问 API 没有返回有效回答");
        }

        JsonNode message = response.path("choices")
                .get(0)
                .path("message");
        if (!message.isObject()) {
            throw new IllegalStateException("千问 API 返回的 message 格式无效");
        }
        return response.path("choices").get(0);
    }

    /** 检查完整响应并提取第一条回答正文；缺失、非文本或空白正文均视为无效回答。 */
    private String extractAnswer(JsonNode response) {
        JsonNode content = extractChoice(response).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new IllegalStateException("千问 API 没有返回有效回答正文");
        }
        return content.asText();
    }

    /** 从 assistant 消息提取工具调用数组；缺失或 null 时返回空数组，类型错误时抛出异常。 */
    private JsonNode getToolCalls(JsonNode message) {
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isMissingNode() || toolCalls.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (!toolCalls.isArray()) {
            throw new IllegalStateException("千问返回的 tool_calls 格式无效");
        }
        return toolCalls;
    }

}
