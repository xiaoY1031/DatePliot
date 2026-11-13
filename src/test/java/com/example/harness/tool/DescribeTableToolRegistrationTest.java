package com.example.harness.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DescribeTableToolRegistrationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void autoRegistersDescribeTableTool() {
        assertInstanceOf(DescribeTableTool.class,
                toolRegistry.find("describe_table").orElseThrow());
        assertTrue(toolRegistry.listDefinitions().stream()
                .anyMatch(definition -> "describe_table".equals(definition.name())));
    }
}
