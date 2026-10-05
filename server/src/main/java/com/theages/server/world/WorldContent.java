package com.theages.server.world;

import com.theages.server.world.item.ItemTemplate;
import com.theages.server.world.item.ShopDefinition;
import java.util.Map;

/** 已驗證過的遊戲內容（所有引用都存在），依 id 索引。由 {@link WorldProperties#validatedContent()} 產生。 */
public record WorldContent(Map<String, NpcTemplate> npcs, Map<String, ItemTemplate> items,
                           Map<String, ShopDefinition> shops) {

    public WorldContent {
        npcs = Map.copyOf(npcs);
        items = Map.copyOf(items);
        shops = Map.copyOf(shops);
    }

    public ShopDefinition shopOf(NpcTemplate merchant) {
        return shops.get(merchant.shop());
    }
}
