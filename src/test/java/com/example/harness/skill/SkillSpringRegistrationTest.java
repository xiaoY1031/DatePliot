package com.example.harness.skill;

import com.example.harness.context.ContextManager;
import com.example.harness.task.TaskStore;
import com.example.harness.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SkillSpringRegistrationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ContextManager contextManager;

    @Autowired
    private TaskStore taskStore;

    @Test
    void discoversSkillsAndRegistersLoadToolThroughSpring() {
        assertInstanceOf(LoadSkillTool.class,
                toolRegistry.find("load_skill").orElseThrow());

        String catalog = contextManager.buildMessages(taskStore.create("检查可用能力"))
                .get(1)
                .content();
        assertTrue(catalog.contains("database-exploration"));
        assertTrue(catalog.contains("data-analysis"));
        assertTrue(catalog.contains("report-generation"));
        assertTrue(!catalog.contains("## 工作流程"));
    }
}
