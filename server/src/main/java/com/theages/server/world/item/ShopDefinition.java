package com.theages.server.world.item;

import java.util.List;

/**
 * 商店，由 {@code world/content.yml} 載入，商人 NPC 以 {@code shop: <id>} 引用。
 *
 * @param buyRate 收購價 = 物品價值 × buyRate（無條件捨去，至少 1）
 * @param buys    收購的物品類型；空 = 什麼都不收
 * @param sells   販賣的物品
 */
public record ShopDefinition(String id, String name, double buyRate, List<ItemType> buys, List<ShopListing> sells) {

    public ShopDefinition {
        buys = buys == null ? List.of() : List.copyOf(buys);
        sells = sells == null ? List.of() : List.copyOf(sells);
    }

    /** 商人願意出多少收購一個；0 = 不收。 */
    public int buyPrice(ItemTemplate item) {
        if (!buys.contains(item.type()) || item.value() <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.floor(item.value() * buyRate));
    }

    /**
     * 商店販賣的一項物品。
     *
     * @param price 售價；未指定時為物品價值
     */
    public record ShopListing(String item, Integer price) {

        public int priceFor(ItemTemplate template) {
            return price != null ? price : template.value();
        }
    }
}
