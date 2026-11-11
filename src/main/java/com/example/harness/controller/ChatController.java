package com.example.harness.controller;

import com.example.harness.agent.AgentRuntime;
import org.springframework.web.bind.annotation.*;

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
    public Map<String, String> chat(
            @RequestBody ChatRequest request) {

        String result = agentRuntime.run(request.message());
        return Map.of("answer", result);
    }

    public record ChatRequest(String message) {
    }
}
