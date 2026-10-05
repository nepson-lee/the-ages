package com.theages.server.world;

import com.theages.server.world.item.LootEntry;
import java.util.List;
import java.util.Locale;

/**
 * NPC 的靜態資料，由 {@code world/content.yml} 載入。
 *
 * @param goldMin 擊殺後搜出的銅錢下限
 * @param goldMax 擊殺後搜出的銅錢上限
 * @param shop     商店 id；有值的 NPC 是商人，不能攻擊
 * @param friendly 友善 NPC（例如發任務的村長）：不能攻擊、不會主動攻擊
 * @param greeting 和玩家對話時說的話
 */
public record NpcTemplate(
    String id,
    String name,
    List<String> keywords,
    String description,
    int level,
    int maxHp,
    int attack,
    int defense,
    int exp,
    float speed,
    String verb,
    boolean aggressive,
    int respawnSeconds,
    float wanderRadius,
    List<LootEntry> loot,
    int goldMin,
    int goldMax,
    String shop,
    boolean friendly,
    String greeting) {

    public NpcTemplate {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        loot = loot == null ? List.of() : List.copyOf(loot);
    }

    public boolean isMerchant() {
        return shop != null;
    }

    /** 商人與友善 NPC：不能攻擊，點擊是對話。 */
    public boolean isPeaceful() {
        return friendly || isMerchant();
    }

    /** 中文名稱或任一英文代稱相符（不分大小寫）。 */
    boolean matches(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return name.equals(query) || keywords.stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).equals(q));
    }
}
