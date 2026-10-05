package com.theages.server.world.item;

import java.util.List;
import java.util.Locale;

/**
 * 物品的靜態資料，由 {@code world/content.yml} 載入。
 *
 * @param slot     只有裝備需要
 * @param mana     消耗品使用後回復的內力
 * @param value    物品價值（銅錢），商店依此定價與收購；0 = 沒有價值
 * @param maxStack 同一格最多疊幾個；裝備固定為 1
 */
public record ItemTemplate(
    String id,
    String name,
    List<String> keywords,
    String description,
    ItemType type,
    EquipSlot slot,
    int attack,
    int defense,
    int maxHp,
    int heal,
    int mana,
    int value,
    Integer maxStack) {

    public ItemTemplate {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        if (type == ItemType.EQUIPMENT && slot == null) {
            throw new IllegalArgumentException("裝備 " + id + " 必須指定 slot");
        }
        if (type == ItemType.EQUIPMENT || maxStack == null || maxStack < 1) {
            maxStack = type == ItemType.EQUIPMENT ? 1 : 20;
        }
    }

    public boolean isEquipment() {
        return type == ItemType.EQUIPMENT;
    }

    /** 中文名稱、模板 id 或任一英文代稱相符（不分大小寫）。 */
    public boolean matches(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return name.equals(query) || id.equals(q)
            || keywords.stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).equals(q));
    }

    /** 給玩家看的屬性摘要，例如「攻擊 +6、防禦 +2」。 */
    public String statSummary() {
        StringBuilder sb = new StringBuilder();
        append(sb, "攻擊", attack);
        append(sb, "防禦", defense);
        append(sb, "生命上限", maxHp);
        if (heal > 0) {
            append(sb, "回復生命", heal);
        }
        if (mana > 0) {
            append(sb, "回復內力", mana);
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, int value) {
        if (value == 0) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("、");
        }
        sb.append(label).append(value > 0 ? " +" : " ").append(value);
    }
}
