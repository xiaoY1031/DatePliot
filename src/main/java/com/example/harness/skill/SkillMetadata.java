package com.example.harness.skill;

/** Skill 的轻量元信息，用于目录展示和模型选择。 */
public record SkillMetadata(String name, String description) {

    public SkillMetadata {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill 名称不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Skill 描述不能为空：" + name);
        }
    }
}
