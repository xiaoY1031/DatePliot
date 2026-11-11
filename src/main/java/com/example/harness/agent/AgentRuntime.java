package com.example.harness.agent;

import org.springframework.stereotype.Component;

/** 对外提供稳定的 Agent 执行入口，当前委托统一的 AgentLoop。 */
@Component
public class AgentRuntime {

    private final AgentLoop agentLoop;

    public AgentRuntime(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    public String run(String userMessage) {
        return agentLoop.run(userMessage);
    }
}
