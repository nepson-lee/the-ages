package com.theages.server.character;

import com.theages.server.world.quest.QuestRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CharacterQuestRepository extends JpaRepository<CharacterQuest, Long> {

    List<CharacterQuest> findByCharacterIdOrderById(Long characterId);

    @Modifying
    @Query("delete from CharacterQuest q where q.characterId = :characterId")
    void deleteByCharacterId(Long characterId);

    default List<QuestRecord> loadRecords(Long characterId) {
        return findByCharacterIdOrderById(characterId).stream().map(CharacterQuest::toRecord).toList();
    }

    /** 整份覆寫角色的任務進度；呼叫端必須在交易內。 */
    default void replaceAll(Long characterId, List<QuestRecord> records) {
        deleteByCharacterId(characterId);
        saveAll(records.stream().map(r -> new CharacterQuest(characterId, r)).toList());
    }
}
