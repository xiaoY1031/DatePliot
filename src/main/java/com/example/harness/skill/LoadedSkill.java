package com.example.harness.skill;

/** 已按需读取完整指令的 Skill。 */
public record LoadedSkill(SkillMetadata metadata, String instructions) {

    public LoadedSkill {
        if (metadata == null) {
            throw new IllegalArgumentException("Skill 元信息不能为空");
        }
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("Skill Instructions 不能为空：" + metadata.name());
        }
    }
}
