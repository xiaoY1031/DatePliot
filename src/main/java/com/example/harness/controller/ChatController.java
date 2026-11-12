package com.example.harness.controller;

import com.example.harness.agent.AgentRuntime;
import com.example.harness.task.TaskStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentRuntime agentRuntime;

    /** 由 Spring 注入 Agent Runtime，控制器不再依赖具体模型客户端。 */
    public ChatController(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    /** 接收 message，执行工具并将结果交回模型，将最终回答封装为 answer 返回。 */
    @PostMapping
    public ResponseEntity<Map<String, String>> chat(
            @RequestHeader(value = "X-Task-Id", required = false) String taskId,
            @RequestBody ChatRequest request) {

        try {
            AgentRuntime.AgentResult result = agentRuntime.run(taskId, request.message());
            return ResponseEntity.ok()
                    .header("X-Task-Id", result.taskId())
                    .body(Map.of("answer", result.answer()));
        } catch (TaskStore.UnknownTaskException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    public record ChatRequest(String message) {
    }
}
