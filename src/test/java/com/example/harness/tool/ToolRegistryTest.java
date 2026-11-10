package com.example.harness.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolRegistryTest {

    @Test
    void registersFindsAndListsTools() {
        ProjectInfoTool tool = new ProjectInfoTool();
        ToolRegistry registry = new ToolRegistry(List.of());

        registry.register(tool);

        assertSame(tool, registry.find("get_project_info").orElseThrow());
        assertEquals(List.of(tool), registry.listTools());
        assertEquals(List.of(tool.definition()), registry.listDefinitions());
    }

    @Test
    void rejectsDuplicateToolNames() {
        ToolRegistry registry = new ToolRegistry(List.of(new ProjectInfoTool()));

        var error = assertThrows(IllegalStateException.class,
                () -> registry.register(new ProjectInfoTool()));

        assertEquals("重复的工具名称：get_project_info", error.getMessage());
    }
}
