package com.example.harness.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ExecuteReadonlySqlToolRegistrationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void autoRegistersExecuteReadonlySqlTool() {
        assertInstanceOf(ExecuteReadonlySqlTool.class,
                toolRegistry.find("execute_readonly_sql").orElseThrow());
        assertTrue(toolRegistry.listDefinitions().stream()
                .anyMatch(definition -> "execute_readonly_sql".equals(definition.name())));
    }
}
