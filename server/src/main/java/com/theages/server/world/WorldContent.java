package com.theages.server.world;

import com.theages.server.world.item.ItemTemplate;
import com.theages.server.world.item.ShopDefinition;
import com.theages.server.world.quest.QuestDefinition;
import java.util.List;
import java.util.Map;

/** 已驗證過的遊戲內容（所有引用都存在），依 id 索引。由 {@link WorldProperties#validatedContent()} 產生。 */
public record WorldContent(Map<String, NpcTemplate> npcs, Map<String, ItemTemplate> items,
                           Map<String, ShopDefinition> shops, Map<String, QuestDefinition> quests) {

    public WorldContent {
        npcs = Map.copyOf(npcs);
        items = Map.copyOf(items);
        shops = Map.copyOf(shops);
        quests = Map.copyOf(quests);
    }

    /** 由這位 NPC 發出、或要交給這位 NPC 的任務（依 id 排序，順序固定）。 */
    public List<QuestDefinition> questsAt(NpcTemplate npc) {
        return quests.values().stream()
            .filter(q -> q.giver().equals(npc.id()) || q.turnIn().equals(npc.id()))
            .sorted(java.util.Comparator.comparing(QuestDefinition::id))
            .toList();
    }

    public ShopDefinition shopOf(NpcTemplate merchant) {
        return shops.get(merchant.shop());
    }
}
