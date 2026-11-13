package com.example.harness.skill;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 发现本地 Skill，并按需读取其完整 SKILL.md。 */
@Component
public class SkillLoader {

    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    private final Path skillsRoot;
    private final Map<String, DiscoveredSkill> skills;

    public SkillLoader(
            @Value("${datapilot.skills-directory:skills}") String skillsDirectory) {
        this.skillsRoot = resolveRoot(skillsDirectory);
        this.skills = discoverSkills();
    }

    /** 返回已发现 Skill 的元信息，不读取任何 Skill 的完整正文。 */
    public List<SkillMetadata> listMetadata() {
        return skills.values().stream()
                .map(DiscoveredSkill::metadata)
                .sorted(Comparator.comparing(SkillMetadata::name))
                .toList();
    }

    /** 根据 Skill 名称按需读取完整 Instructions。 */
    public LoadedSkill load(String name) {
        validateRequestedName(name);
        DiscoveredSkill discovered = skills.get(name);
        if (discovered == null) {
            throw new SkillNotFoundException(name);
        }

        Path skillFile = verifySkillFile(discovered.directory(), discovered.skillFile());
        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            ParsedSkill parsed = parseSkillFile(skillFile, content);
            if (!parsed.metadata().name().equals(name)) {
                throw invalidSkill(skillFile, "Skill 名称与已发现名称不一致");
            }
            return new LoadedSkill(parsed.metadata(), parsed.instructions());
        } catch (IOException e) {
            throw new IllegalStateException("读取 Skill 失败：" + skillFile, e);
        }
    }

    private Path resolveRoot(String configuredDirectory) {
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            throw new IllegalArgumentException("Skill 目录不能为空");
        }
        Path configured = Path.of(configuredDirectory);
        Path absolute = configured.isAbsolute()
                ? configured.normalize()
                : Path.of(System.getProperty("user.dir")).resolve(configured).normalize();
        try {
            if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Skill 目录不存在或不是目录：" + absolute);
            }
            return absolute.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("解析 Skill 目录失败：" + absolute, e);
        }
    }

    private Map<String, DiscoveredSkill> discoverSkills() {
        Map<String, DiscoveredSkill> discovered = new LinkedHashMap<>();
        try (var children = Files.list(skillsRoot)) {
            List<Path> directories = children
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path directory : directories) {
                Path realDirectory = verifyInsideRoot(directory, "Skill 目录");
                Path skillFile = realDirectory.resolve(SKILL_FILE_NAME).normalize();
                if (!Files.exists(skillFile, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                skillFile = verifySkillFile(realDirectory, skillFile);
                SkillMetadata metadata = readMetadata(skillFile);
                DiscoveredSkill previous = discovered.putIfAbsent(
                        metadata.name(),
                        new DiscoveredSkill(metadata, realDirectory, skillFile));
                if (previous != null) {
                    throw new IllegalStateException("重复的 Skill 名称：" + metadata.name()
                            + "（文件：" + previous.skillFile() + " 和 " + skillFile + "）");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 Skill 目录失败：" + skillsRoot, e);
        }
        return Map.copyOf(discovered);
    }

    private Path verifySkillFile(Path skillDirectory, Path skillFile) {
        Path realDirectory = verifyInsideRoot(skillDirectory, "Skill 目录");
        Path normalizedFile = skillFile.normalize();
        if (!normalizedFile.startsWith(realDirectory)) {
            throw new IllegalStateException("Skill 文件越界：" + skillFile);
        }
        Path realFile = verifyInsideRoot(normalizedFile, "Skill 文件");
        if (!realFile.getFileName().toString().equals(SKILL_FILE_NAME)
                || !realFile.startsWith(realDirectory)
                || !Files.isRegularFile(realFile)) {
            throw new IllegalStateException("Skill 文件无效：" + skillFile);
        }
        return realFile;
    }

    private Path verifyInsideRoot(Path path, String description) {
        try {
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(skillsRoot)) {
                throw new IllegalStateException(description + "越界：" + path);
            }
            return realPath;
        } catch (IOException e) {
            throw new IllegalStateException(description + "不存在或无法访问：" + path, e);
        }
    }

    private SkillMetadata readMetadata(Path skillFile) {
        try (BufferedReader reader = Files.newBufferedReader(skillFile, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (!"---".equals(firstLine == null ? null : firstLine.trim())) {
                throw invalidSkill(skillFile, "必须以 --- 开始 front matter");
            }
            List<String> frontMatterLines = new java.util.ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if ("---".equals(line.trim())) {
                    return parseMetadata(skillFile, frontMatterLines);
                }
                frontMatterLines.add(line);
            }
            throw invalidSkill(skillFile, "front matter 缺少结束标记 ---");
        } catch (IOException e) {
            throw new IllegalStateException("读取 Skill 元信息失败：" + skillFile, e);
        }
    }

    private ParsedSkill parseSkillFile(Path skillFile, String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        if (lines.length == 0 || !"---".equals(lines[0].trim())) {
            throw invalidSkill(skillFile, "必须以 --- 开始 front matter");
        }

        int closingIndex = -1;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) {
                closingIndex = i;
                break;
            }
        }
        if (closingIndex < 0) {
            throw invalidSkill(skillFile, "front matter 缺少结束标记 ---");
        }

        SkillMetadata metadata = parseMetadata(skillFile,
                java.util.Arrays.asList(lines).subList(1, closingIndex));
        String instructions = String.join("\n", java.util.Arrays.copyOfRange(
                lines, closingIndex + 1, lines.length)).trim();
        if (instructions.isBlank()) {
            throw invalidSkill(skillFile, "Skill Instructions 不能为空");
        }
        return new ParsedSkill(metadata, instructions);
    }

    private SkillMetadata parseMetadata(Path skillFile, List<String> frontMatterLines) {
        Map<String, String> frontMatter = new LinkedHashMap<>();
        for (String line : frontMatterLines) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw invalidSkill(skillFile, "front matter 必须使用 key: value 格式");
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (!(key.equals("name") || key.equals("description"))) {
                throw invalidSkill(skillFile, "不支持的 front matter 字段：" + key);
            }
            if (value.isBlank()) {
                throw invalidSkill(skillFile, "name 和 description 必须是非空单行字段");
            }
            if (frontMatter.putIfAbsent(key, value) != null) {
                throw invalidSkill(skillFile, "front matter 字段重复：" + key);
            }
        }

        String name = frontMatter.get("name");
        String description = frontMatter.get("description");
        if (name == null || description == null) {
            throw invalidSkill(skillFile, "必须同时提供 name 和 description");
        }
        if (!VALID_NAME.matcher(name).matches()) {
            throw invalidSkill(skillFile, "Skill 名称非法：" + name);
        }
        return new SkillMetadata(name, description);
    }

    private void validateRequestedName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Skill 名称非法：" + name);
        }
    }

    private IllegalStateException invalidSkill(Path skillFile, String reason) {
        return new IllegalStateException("Skill 文件格式无效：" + skillFile + "，" + reason);
    }

    private record DiscoveredSkill(
            SkillMetadata metadata,
            Path directory,
            Path skillFile) {
    }

    private record ParsedSkill(SkillMetadata metadata, String instructions) {
    }

    public static class SkillNotFoundException extends IllegalArgumentException {
        public SkillNotFoundException(String name) {
            super("Skill 不存在：" + name);
        }
    }
}
