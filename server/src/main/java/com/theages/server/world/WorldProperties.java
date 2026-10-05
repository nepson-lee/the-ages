package com.theages.server.world;

import com.theages.server.world.item.ItemTemplate;
import com.theages.server.world.item.LootEntry;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("theages.world")
public record WorldProperties(int tickRate, String startingZone, List<String> startingItems,
                             List<ItemTemplate> itemTemplates, List<NpcTemplate> npcTemplates,
                             List<ZoneDefinition> zones) {

    public WorldProperties {
        startingItems = startingItems == null ? List.of() : List.copyOf(startingItems);
        itemTemplates = itemTemplates == null ? List.of() : List.copyOf(itemTemplates);
        npcTemplates = npcTemplates == null ? List.of() : List.copyOf(npcTemplates);
    }

    public ZoneDefinition startingZoneDefinition() {
        return zones.stream()
            .filter(z -> z.id().equals(startingZone))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("找不到起始區域：" + startingZone));
    }

    /** 依 id 索引物品，並檢查掉寶表與出生物品引用的物品都存在。 */
    public Map<String, ItemTemplate> validatedItems() {
        Map<String, ItemTemplate> byId = itemTemplates.stream()
            .collect(Collectors.toUnmodifiableMap(ItemTemplate::id, Function.identity()));
        for (NpcTemplate npc : npcTemplates) {
            for (LootEntry loot : npc.loot()) {
                if (!byId.containsKey(loot.item())) {
                    throw new IllegalStateException("NPC " + npc.id() + " 的掉寶表引用了不存在的物品：" + loot.item());
                }
            }
        }
        for (String id : startingItems) {
            if (!byId.containsKey(id)) {
                throw new IllegalStateException("starting-items 引用了不存在的物品：" + id);
            }
        }
        return byId;
    }

    /** 依 id 索引模板，並檢查每個區域引用的模板都存在。 */
    public Map<String, NpcTemplate> validatedTemplates() {
        Map<String, NpcTemplate> byId = npcTemplates.stream()
            .collect(Collectors.toUnmodifiableMap(NpcTemplate::id, Function.identity()));
        for (ZoneDefinition zone : zones) {
            for (ZoneDefinition.NpcSpawn spawn : zone.npcs()) {
                if (!byId.containsKey(spawn.template())) {
                    throw new IllegalStateException("區域 " + zone.id() + " 引用了不存在的 NPC 模板：" + spawn.template());
                }
            }
        }
        return byId;
    }
}
