package com.theages.server.world.skill;

import java.util.List;
import java.util.Locale;

/**
 * 技能，由 {@code world/content.yml} 載入。
 *
 * @param level           學習所需等級
 * @param trainer         教這個技能的 NPC 模板 id
 * @param price           學費（銅錢）
 * @param mpCost          施放消耗的內力
 * @param cooldownSeconds 冷卻秒數
 * @param power           strike/aoe：傷害倍率（一般攻擊傷害 × power）；heal：基礎回復量
 * @param powerPerLevel   heal：每一級多回復多少
 * @param radius          aoe：範圍（公尺）；heal：可以治療的距離
 * @param attack          buff：攻擊加成
 * @param defense         buff：防禦加成
 * @param durationSeconds buff：持續秒數
 */
public record SkillDefinition(
    String id,
    String name,
    List<String> keywords,
    String description,
    SkillType type,
    int level,
    String trainer,
    int price,
    int mpCost,
    double cooldownSeconds,
    double power,
    int powerPerLevel,
    float radius,
    int attack,
    int defense,
    int durationSeconds) {

    public SkillDefinition {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        level = Math.max(1, level);
        if (type == null) {
            throw new IllegalArgumentException("技能 " + id + " 必須指定 type");
        }
    }

    /** 中文名稱、id 或任一英文代稱相符（不分大小寫）。 */
    public boolean matches(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return name.equals(query) || id.equals(q) || keywords.stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).equals(q));
    }

    /** heal：施放者等級 level 時的回復量。 */
    public int healAmount(int casterLevel) {
        return (int) Math.round(power + powerPerLevel * casterLevel);
    }

    /** 給玩家看的效果摘要。 */
    public String summary() {
        String effect = switch (type) {
            case STRIKE -> String.format("對目標造成 %.0f%% 傷害", power * 100);
            case AOE -> String.format("對 %.1f 公尺內所有敵人造成 %.0f%% 傷害", radius, power * 100);
            case HEAL -> "回復 " + (int) power + " + 等級×" + powerPerLevel + " 點生命";
            case BUFF -> (attack != 0 ? "攻擊 +" + attack + " " : "") + (defense != 0 ? "防禦 +" + defense + " " : "")
                + "持續 " + durationSeconds + " 秒";
        };
        return effect + String.format("（內力 %d，冷卻 %.0f 秒）", mpCost, cooldownSeconds);
    }
}
