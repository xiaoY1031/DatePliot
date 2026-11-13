package com.example.harness.skill;

import com.example.harness.tool.ToolExecutor;
import com.example.harness.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadSkillToolTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsOneSkillThroughTheExistingToolRegistryAndExecutor() throws IOException {
        SkillLoader loader = loaderWithSkill();
        LoadSkillTool loadSkillTool = new LoadSkillTool(loader);
        ToolRegistry registry = new ToolRegistry(List.of(loadSkillTool));
        ToolExecutor executor = new ToolExecutor(registry, new ObjectMapper());

        var result = executor.execute("call-1", "load_skill", "{\"name\":\"data-analysis\"}");

        assertTrue(result.success());
        assertTrue(result.content().contains("ANALYSIS_RULES"));
        assertFalse(result.content().contains("name: data-analysis"));
        assertEquals("load_skill", registry.listDefinitions().get(0).name());
    }

    @Test
    void returnsExplicitErrorsForUnknownAndInvalidSkillNames() throws IOException {
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(List.of(new LoadSkillTool(loaderWithSkill()))),
                new ObjectMapper());

        var unknown = executor.execute("call-1", "load_skill", "{\"name\":\"missing-skill\"}");
        var invalid = executor.execute("call-2", "load_skill", "{\"name\":\"../outside\"}");
        var missingName = executor.execute("call-3", "load_skill", "{}");

        assertFalse(unknown.success());
        assertEquals("UNKNOWN_SKILL", unknown.errorCode());
        assertTrue(unknown.content().contains("Skill 不存在：missing-skill"));
        assertFalse(invalid.success());
        assertEquals("INVALID_ARGUMENTS", invalid.errorCode());
        assertFalse(missingName.success());
        assertEquals("INVALID_ARGUMENTS", missingName.errorCode());
    }

    private SkillLoader loaderWithSkill() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("skills-" + System.nanoTime()));
        Path skillDirectory = Files.createDirectory(root.resolve("data-analysis"));
        Files.writeString(skillDirectory.resolve("SKILL.md"), """
                ---
                name: data-analysis
                description: Analyze supplied data.
                ---
                # ANALYSIS_RULES
                Use evidence and do not invent results.
                """);
        return new SkillLoader(root.toString());
    }
}
