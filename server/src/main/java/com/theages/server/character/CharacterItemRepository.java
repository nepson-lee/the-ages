package com.theages.server.character;

import com.theages.server.world.item.ItemRecord;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CharacterItemRepository extends JpaRepository<CharacterItem, Long> {

    List<CharacterItem> findByCharacterIdOrderBySortOrder(Long characterId);

    @Modifying
    @Query("delete from CharacterItem i where i.characterId = :characterId")
    void deleteByCharacterId(Long characterId);

    default List<ItemRecord> loadRecords(Long characterId) {
        return findByCharacterIdOrderBySortOrder(characterId).stream().map(CharacterItem::toRecord).toList();
    }

    /** 整份覆寫角色的物品；呼叫端必須在交易內。 */
    default void replaceAll(Long characterId, List<ItemRecord> records) {
        deleteByCharacterId(characterId);
        saveAll(IntStream.range(0, records.size())
            .mapToObj(i -> new CharacterItem(characterId, records.get(i), i))
            .toList());
    }
}
