package com.theages.server.world;

import com.theages.server.world.item.ItemTemplate;
import com.theages.server.world.item.LootEntry;
import com.theages.server.world.item.ShopDefinition;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("theages.world")
public record WorldProperties(int tickRate, String startingZone, List<String> startingItems, int startingGold,
                             List<ItemTemplate> itemTemplates, List<ShopDefinition> shops,
                             List<NpcTemplate> npcTemplates, List<ZoneDefinition> zones) {

    public WorldProperties {
        startingItems = startingItems == null ? List.of() : List.copyOf(startingItems);
        itemTemplates = itemTemplates == null ? List.of() : List.copyOf(itemTemplates);
        shops = shops == null ? List.of() : List.copyOf(shops);
        npcTemplates = npcTemplates == null ? List.of() : List.copyOf(npcTemplates);
    }

    public ZoneDefinition startingZoneDefinition() {
        return zones.stream()
            .filter(z -> z.id().equals(startingZone))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("找不到起始區域：" + startingZone));
    }

    /** 依 id 索引所有內容，並檢查每個引用都存在；有問題就拒絕啟動。 */
    public WorldContent validatedContent() {
        Map<String, ItemTemplate> items = index(itemTemplates, ItemTemplate::id);
        Map<String, ShopDefinition> shopsById = index(shops, ShopDefinition::id);
        Map<String, NpcTemplate> npcs = index(npcTemplates, NpcTemplate::id);

        for (String id : startingItems) {
            require(items.containsKey(id), "starting-items 引用了不存在的物品：" + id);
        }
        for (ShopDefinition shop : shops) {
            for (ShopDefinition.ShopListing listing : shop.sells()) {
                require(items.containsKey(listing.item()), "商店 " + shop.id() + " 販賣不存在的物品：" + listing.item());
                require(listing.priceFor(items.get(listing.item())) > 0,
                    "商店 " + shop.id() + " 的 " + listing.item() + " 沒有售價（請設定物品 value 或 price）");
            }
        }
        for (NpcTemplate npc : npcTemplates) {
            for (LootEntry loot : npc.loot()) {
                require(items.containsKey(loot.item()), "NPC " + npc.id() + " 的掉寶表引用了不存在的物品：" + loot.item());
            }
            if (npc.isMerchant()) {
                require(shopsById.containsKey(npc.shop()), "NPC " + npc.id() + " 引用了不存在的商店：" + npc.shop());
            }
            require(npc.goldMax() >= npc.goldMin(), "NPC " + npc.id() + " 的 gold-max 小於 gold-min");
        }
        Map<String, ZoneDefinition> zonesById = index(zones, ZoneDefinition::id);
        for (ZoneDefinition zone : zones) {
            for (ZoneDefinition.NpcSpawn spawn : zone.npcs()) {
                require(npcs.containsKey(spawn.template()), "區域 " + zone.id() + " 引用了不存在的 NPC 模板：" + spawn.template());
            }
            for (ZoneDefinition.Exit exit : zone.exits()) {
                ZoneDefinition to = zonesById.get(exit.to());
                require(to != null, "區域 " + zone.id() + " 的出口「" + exit.name() + "」通往不存在的區域：" + exit.to());
                require(zone.contains(exit.x(), exit.z()), "區域 " + zone.id() + " 的出口「" + exit.name() + "」在區域範圍外");
                require(to.contains(exit.toX(), exit.toZ()),
                    "區域 " + zone.id() + " 的出口「" + exit.name() + "」的抵達點在 " + to.id() + " 範圍外");
            }
        }
        return new WorldContent(npcs, items, shopsById);
    }

    private static <T> Map<String, T> index(List<T> list, Function<T, String> id) {
        return list.stream().collect(Collectors.toUnmodifiableMap(id, Function.identity()));
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException(message);
        }
    }
}
