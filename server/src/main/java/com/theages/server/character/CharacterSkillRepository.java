package com.theages.server.character;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CharacterSkillRepository extends JpaRepository<CharacterSkill, Long> {

    List<CharacterSkill> findByCharacterIdOrderById(Long characterId);

    @Modifying
    @Query("delete from CharacterSkill s where s.characterId = :characterId")
    void deleteByCharacterId(Long characterId);

    default List<String> loadIds(Long characterId) {
        return findByCharacterIdOrderById(characterId).stream().map(CharacterSkill::getSkillId).toList();
    }

    /** 整份覆寫角色學會的技能；呼叫端必須在交易內。 */
    default void replaceAll(Long characterId, List<String> skillIds) {
        deleteByCharacterId(characterId);
        saveAll(skillIds.stream().map(id -> new CharacterSkill(characterId, id)).toList());
    }
}
