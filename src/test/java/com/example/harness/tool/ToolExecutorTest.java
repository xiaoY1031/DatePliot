package com.example.harness.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "qwen.api-key=test-key",
        "qwen.base-url=https://example.test/v1",
        "qwen.model=qwen-plus"
})
class ToolExecutorTest {

    @Autowired
    private ToolExecutor toolExecutor;

    @Autowired
    private ToolRegistry toolRegistry;

    /** 验证 Spring 注入的执行器能调用本地工具；不发送真实模型 API 请求。 */
    @Test
    void shouldExecuteProjectInfoTool() {
        String result = toolExecutor.execute("get_project_info");

        assertTrue(result.contains("Spring Boot"));
        assertTrue(result.contains("Redis"));
        assertTrue(toolRegistry.find("get_project_info").isPresent());
        assertTrue(toolRegistry.find("list_files").isPresent());
    }

    @Test
    void returnsStructuredErrorForUnknownTool() {
        ToolResult result = toolExecutor.execute("call_1", "missing_tool", "{}");

        assertFalse(result.success());
        assertEquals("UNKNOWN_TOOL", result.errorCode());
        assertTrue(result.toModelContent().contains("missing_tool"));
    }

    @Test
    void rejectsMalformedAndUnexpectedArguments() {
        ToolResult malformed = toolExecutor.execute("call_1", "get_project_info", "not-json");
        ToolResult unexpected = toolExecutor.execute(
                "call_2", "get_project_info", "{\"unexpected\":true}");

        assertEquals("INVALID_ARGUMENTS", malformed.errorCode());
        assertEquals("INVALID_ARGUMENTS", unexpected.errorCode());
        assertTrue(unexpected.content().contains("unexpected"));
    }
}
