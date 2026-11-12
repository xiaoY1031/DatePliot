package com.example.harness.context;

import com.example.harness.llm.model.LlmMessage;
import com.example.harness.task.TaskState;
import com.example.harness.task.TaskStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 统一管理 System Prompt、任务上下文与完整对话历史。 */
@Component
public class ContextManager {

    private final TaskStore taskStore;
    private final String systemPrompt;

    public ContextManager(
            TaskStore taskStore,
            @Value("classpath:prompts/system.md") Resource systemPromptResource) {
        this.taskStore = taskStore;
        this.systemPrompt = readPrompt(systemPromptResource);
    }

    /** 每轮请求都重新生成快照，System Prompt 和 Task Context 不写入持久历史。 */
    public List<LlmMessage> buildMessages(TaskState task) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(systemPrompt));
        messages.add(LlmMessage.system("当前任务目标：" + task.goal()));
        messages.addAll(taskStore.history(task));
        return List.copyOf(messages);
    }

    public void appendUserMessage(TaskState task, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("用户消息不能为空");
        }
        taskStore.append(task, LlmMessage.user(content));
        taskStore.recordTurn(task);
    }

    public void appendFinalAnswer(TaskState task, LlmMessage assistantMessage) {
        if (assistantMessage == null
                || assistantMessage.role() != LlmMessage.Role.ASSISTANT
                || !assistantMessage.toolCalls().isEmpty()
                || assistantMessage.content() == null
                || assistantMessage.content().isBlank()) {
            throw new IllegalArgumentException("最终回答必须是包含有效正文的 assistant 消息");
        }
        taskStore.append(task, assistantMessage);
    }

    /** 原子保存 assistant(tool_calls) 及其全部 tool 结果，确保 ID 一一对应。 */
    public void appendToolExchange(
            TaskState task,
            LlmMessage assistantMessage,
            List<LlmMessage> toolMessages) {
        if (assistantMessage == null
                || assistantMessage.role() != LlmMessage.Role.ASSISTANT
                || assistantMessage.toolCalls().isEmpty()) {
            throw new IllegalArgumentException("工具交换必须包含 assistant tool_calls 消息");
        }

        Set<String> expectedIds = new HashSet<>();
        assistantMessage.toolCalls().forEach(call -> expectedIds.add(call.id()));
        if (expectedIds.size() != assistantMessage.toolCalls().size()) {
            throw new IllegalArgumentException("assistant tool_calls 包含重复的调用 ID");
        }
        Set<String> actualIds = new HashSet<>();
        for (LlmMessage toolMessage : toolMessages) {
            if (toolMessage.role() != LlmMessage.Role.TOOL
                    || toolMessage.toolCallId() == null
                    || toolMessage.toolCallId().isBlank()) {
                throw new IllegalArgumentException("工具结果必须包含 tool_call_id");
            }
            if (!actualIds.add(toolMessage.toolCallId())) {
                throw new IllegalArgumentException("存在重复的 tool_call_id：" + toolMessage.toolCallId());
            }
        }
        if (!actualIds.equals(expectedIds)) {
            throw new IllegalArgumentException("工具结果与 assistant tool_calls 不匹配");
        }

        List<LlmMessage> exchange = new ArrayList<>();
        exchange.add(assistantMessage);
        exchange.addAll(toolMessages);
        taskStore.appendAll(task, exchange);
        taskStore.recordToolCalls(task, assistantMessage.toolCalls().size());
    }

    public void recordModelRequest(TaskState task) {
        taskStore.recordModelRequest(task);
    }

    private String readPrompt(Resource resource) {
        try {
            String prompt = resource.getContentAsString(StandardCharsets.UTF_8).trim();
            if (prompt.isBlank()) {
                throw new IllegalStateException("System Prompt 不能为空");
            }
            return prompt;
        } catch (IOException e) {
            throw new IllegalStateException("读取 System Prompt 失败", e);
        }
    }
}

