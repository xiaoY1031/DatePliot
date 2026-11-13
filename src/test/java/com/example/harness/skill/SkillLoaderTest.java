package com.example.harness.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SkillLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversMetadataAndLoadsInstructionsOnlyWhenRequested() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("skills"));
        writeSkill(root, "report", "report-generation", "Create clear reports.", "# Report\nUse evidence.");
        writeSkill(root, "database", "database-exploration", "Explore databases.", "# Explore\nInspect schema.");

        SkillLoader loader = new SkillLoader(root.toString());

        assertEquals(List.of(
                new SkillMetadata("database-exploration", "Explore databases."),
                new SkillMetadata("report-generation", "Create clear reports.")),
                loader.listMetadata());
        assertEquals("# Explore\nInspect schema.",
                loader.load("database-exploration").instructions());
        assertEquals("report-generation", loader.load("report-generation").metadata().name());
    }

    @Test
    void discoversAndIndependentlyLoadsAllThreeProjectSkills() {
        SkillLoader loader = new SkillLoader(Path.of("skills").toAbsolutePath().toString());

        assertEquals(List.of("data-analysis", "database-exploration", "report-generation"),
                loader.listMetadata().stream().map(SkillMetadata::name).toList());
        assertTrue(loader.load("database-exploration").instructions().contains("只读 SQL"));
        assertTrue(loader.load("data-analysis").instructions().contains("数据质量"));
        assertTrue(loader.load("report-generation").instructions().contains("Markdown"));
    }

    @Test
    void reportsUnknownAndInvalidNamesClearly() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("skills"));
        writeSkill(root, "analysis", "data-analysis", "Analyze data.", "Use evidence.");
        SkillLoader loader = new SkillLoader(root.toString());

        var missing = assertThrows(SkillLoader.SkillNotFoundException.class,
                () -> loader.load("unknown-skill"));
        assertTrue(missing.getMessage().contains("Skill 不存在：unknown-skill"));
        assertThrows(IllegalArgumentException.class, () -> loader.load("../outside"));
        assertThrows(IllegalArgumentException.class, () -> loader.load("C:/outside"));
    }

    @Test
    void rejectsDuplicateNamesAndIllegalFrontMatter() throws IOException {
        Path duplicateRoot = Files.createDirectory(temporaryDirectory.resolve("duplicate-skills"));
        writeSkill(duplicateRoot, "one", "same-name", "First.", "Instructions.");
        writeSkill(duplicateRoot, "two", "same-name", "Second.", "Instructions.");
        var duplicate = assertThrows(IllegalStateException.class,
                () -> new SkillLoader(duplicateRoot.toString()));
        assertTrue(duplicate.getMessage().contains("重复的 Skill 名称：same-name"));

        Path invalidRoot = Files.createDirectory(temporaryDirectory.resolve("invalid-skills"));
        Path invalidDirectory = Files.createDirectory(invalidRoot.resolve("invalid"));
        Files.writeString(invalidDirectory.resolve("SKILL.md"),
                "---\nname: ../escape\ndescription: Invalid name.\n---\nInstructions.");
        var invalid = assertThrows(IllegalStateException.class,
                () -> new SkillLoader(invalidRoot.toString()));
        assertTrue(invalid.getMessage().contains("Skill 名称非法：../escape"));
    }

    @Test
    void rejectsMissingRequiredFieldsAndMalformedFrontMatter() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("malformed-skills"));
        Path skillDirectory = Files.createDirectory(root.resolve("analysis"));
        Files.writeString(skillDirectory.resolve("SKILL.md"),
                "---\nname: data-analysis\nunknown: field\n---\nInstructions.");

        var malformed = assertThrows(IllegalStateException.class,
                () -> new SkillLoader(root.toString()));
        assertTrue(malformed.getMessage().contains("front matter"));
    }

    @Test
    void doesNotReadSkillFileSymlinkThatEscapesRoot() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("skills"));
        Path outside = temporaryDirectory.resolve("outside.md");
        Files.writeString(outside, frontMatter("outside-skill", "Outside.", "Do not read."));
        Path skillDirectory = Files.createDirectory(root.resolve("linked-skill"));
        try {
            Files.createSymbolicLink(skillDirectory.resolve("SKILL.md"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "当前系统不允许创建符号链接：" + e.getMessage());
        }

        var error = assertThrows(IllegalStateException.class,
                () -> new SkillLoader(root.toString()));
        assertTrue(error.getMessage().contains("越界"));
    }

    @Test
    void doesNotDiscoverDirectorySymlinkThatEscapesRoot() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("skills"));
        Path outsideDirectory = Files.createDirectory(temporaryDirectory.resolve("outside-skill"));
        Files.writeString(outsideDirectory.resolve("SKILL.md"),
                frontMatter("outside-skill", "Outside.", "Do not read."));
        try {
            Files.createSymbolicLink(root.resolve("linked-skill"), outsideDirectory);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "当前系统不允许创建符号链接：" + e.getMessage());
        }

        var error = assertThrows(IllegalStateException.class,
                () -> new SkillLoader(root.toString()));
        assertTrue(error.getMessage().contains("越界"));
    }

    private void writeSkill(
            Path root,
            String directoryName,
            String name,
            String description,
            String instructions) throws IOException {
        Path directory = Files.createDirectory(root.resolve(directoryName));
        Files.writeString(directory.resolve("SKILL.md"),
                frontMatter(name, description, instructions));
    }

    private String frontMatter(String name, String description, String instructions) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n" + instructions;
    }
}
