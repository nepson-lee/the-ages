package com.theages.server.world.quest;

import java.util.List;
import java.util.Locale;

/**
 * 任務，由 {@code world/content.yml} 載入。
 *
 * @param giver        發任務的 NPC 模板 id
 * @param turnIn       交任務的 NPC 模板 id；未指定時為 giver。指定別人就是「送信」類任務
 * @param level        最低等級
 * @param requires     必須先完成的任務 id
 * @param objectives   目標；可以是空的（例如單純送信）
 * @param repeatable   完成後可以再接
 * @param acceptText   接任務時 NPC 說的話
 * @param completeText 交任務時 NPC 說的話
 */
public record QuestDefinition(
    String id,
    String name,
    String giver,
    String turnIn,
    String description,
    int level,
    List<String> requires,
    List<Objective> objectives,
    Rewards rewards,
    boolean repeatable,
    String acceptText,
    String completeText) {

    public QuestDefinition {
        turnIn = turnIn == null ? giver : turnIn;
        level = Math.max(1, level);
        requires = requires == null ? List.of() : List.copyOf(requires);
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        rewards = rewards == null ? new Rewards(0, 0, List.of()) : rewards;
    }

    /** 名稱或 id 相符。 */
    public boolean matches(String query) {
        return name.equals(query) || id.equals(query.toLowerCase(Locale.ROOT));
    }

    /**
     * 任務目標：kill 與 collect 二選一。
     * <pre>
     * - { kill: chicken, count: 3 }        殺 3 隻老母雞（NPC 模板 id）
     * - { collect: rabbit-fur, count: 2 }  帶 2 份兔毛來（物品 id，交任務時收走）
     * </pre>
     */
    public record Objective(String kill, String collect, Integer count) {

        public Objective {
            if ((kill == null) == (collect == null)) {
                throw new IllegalArgumentException("任務目標必須指定 kill 或 collect 其中一個");
            }
        }

        public boolean isKill() {
            return kill != null;
        }

        public String target() {
            return isKill() ? kill : collect;
        }

        public int required() {
            return count == null ? 1 : Math.max(1, count);
        }
    }

    public record Rewards(int exp, int gold, List<ItemReward> items) {

        public Rewards {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ItemReward(String item, Integer count) {

        public int countOrOne() {
            return count == null ? 1 : Math.max(1, count);
        }
    }
}
