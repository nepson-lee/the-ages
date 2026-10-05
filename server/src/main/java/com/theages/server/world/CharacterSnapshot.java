package com.theages.server.world;

import com.theages.server.world.item.ItemRecord;
import com.theages.server.world.quest.QuestRecord;
import java.util.List;

/** 要寫回資料庫的角色狀態。 */
public record CharacterSnapshot(long characterId, String zoneId, float x, float z, int level, int exp, int hp,
                                int gold, List<ItemRecord> items, List<QuestRecord> quests) {
}
