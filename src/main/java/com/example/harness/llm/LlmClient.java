package com.example.harness.llm;

import com.example.harness.llm.model.LlmRequest;
import com.example.harness.llm.model.LlmResponse;

public interface LlmClient {
    /** 发送完整的结构化模型请求，返回解析后的 assistant 消息。 */
    LlmResponse complete(LlmRequest request);
}

