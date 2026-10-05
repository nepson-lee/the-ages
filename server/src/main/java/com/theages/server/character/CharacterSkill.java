package com.theages.server.character;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "character_skill")
public class CharacterSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    @Column(name = "skill_id", nullable = false, length = 64)
    private String skillId;

    protected CharacterSkill() {
    }

    public CharacterSkill(Long characterId, String skillId) {
        this.characterId = characterId;
        this.skillId = skillId;
    }

    public String getSkillId() {
        return skillId;
    }
}
