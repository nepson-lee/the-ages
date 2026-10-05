package com.theages.server.world.quest;

import com.theages.server.world.item.Inventory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 玩家的任務日誌：進行中的任務與已完成的任務。
 * 只能在玩家所屬區域的 tick 執行緒存取。
 */
public final class QuestLog {

    public static final int MAX_ACTIVE = 10;

    /** 進行中的任務。kills[i] 是第 i 個目標的擊殺數（收集目標不用）。 */
    public static final class ActiveQuest {
        private final QuestDefinition definition;
        private final int[] kills;

        ActiveQuest(QuestDefinition definition, int[] kills) {
            this.definition = definition;
            this.kills = kills;
        }

        public QuestDefinition definition() {
            return definition;
        }
    }

    private final Map<String, ActiveQuest> active = new LinkedHashMap<>();
    private final Set<String> completed = new LinkedHashSet<>();
    private boolean dirty = true;

    /** 從存檔還原。內容檔已刪除的任務會被略過並回報給 onUnknown。 */
    public static QuestLog fromRecords(List<QuestRecord> records, Map<String, QuestDefinition> quests,
                                       Consumer<String> onUnknown) {
        QuestLog log = new QuestLog();
        for (QuestRecord r : records) {
            QuestDefinition q = quests.get(r.questId());
            if (q == null) {
                onUnknown.accept(r.questId());
            } else if (r.completed()) {
                log.completed.add(q.id());
            } else {
                log.active.put(q.id(), new ActiveQuest(q, parseKills(r.progress(), q)));
            }
        }
        return log;
    }

    public List<QuestRecord> toRecords() {
        List<QuestRecord> records = new ArrayList<>();
        completed.forEach(id -> records.add(new QuestRecord(id, true, "")));
        active.values().forEach(a -> records.add(new QuestRecord(a.definition.id(), false,
            Arrays.stream(a.kills).mapToObj(Integer::toString).collect(Collectors.joining(",")))));
        return records;
    }

    // ===== 接任務 =====

    /** 不能接的原因；可以接時回傳 empty。 */
    public Optional<String> whyCannotAccept(QuestDefinition q, int level) {
        if (active.containsKey(q.id())) {
            return Optional.of("你已經接了「" + q.name() + "」。");
        }
        if (completed.contains(q.id())) {
            return Optional.of("你已經完成過「" + q.name() + "」了。");
        }
        if (level < q.level()) {
            return Optional.of("「" + q.name() + "」需要 " + q.level() + " 級才能接。");
        }
        if (!completed.containsAll(q.requires())) {
            return Optional.of("你還沒有資格接「" + q.name() + "」。");
        }
        if (active.size() >= MAX_ACTIVE) {
            return Optional.of("你同時進行的任務太多了（最多 " + MAX_ACTIVE + " 個）。");
        }
        return Optional.empty();
    }

    public boolean isAvailable(QuestDefinition q, int level) {
        return whyCannotAccept(q, level).isEmpty();
    }

    public void accept(QuestDefinition q) {
        active.put(q.id(), new ActiveQuest(q, new int[q.objectives().size()]));
        dirty = true;
    }

    // ===== 進度 =====

    /** 殺了一隻 npcTemplateId；有任何任務因此前進就回傳 true。 */
    public boolean onKill(String npcTemplateId) {
        boolean changed = false;
        for (ActiveQuest a : active.values()) {
            List<QuestDefinition.Objective> objectives = a.definition.objectives();
            for (int i = 0; i < objectives.size(); i++) {
                QuestDefinition.Objective o = objectives.get(i);
                if (o.isKill() && o.target().equals(npcTemplateId) && a.kills[i] < o.required()) {
                    a.kills[i]++;
                    changed = true;
                }
            }
        }
        dirty |= changed;
        return changed;
    }

    /** 第 i 個目標目前的進度（不超過需求數）。收集目標看背包裡（未裝備的）數量。 */
    public int progress(ActiveQuest a, int i, Inventory inventory) {
        QuestDefinition.Objective o = a.definition.objectives().get(i);
        int current = o.isKill() ? a.kills[i] : inventory.count(o.target());
        return Math.min(current, o.required());
    }

    public boolean isReady(ActiveQuest a, Inventory inventory) {
        for (int i = 0; i < a.definition.objectives().size(); i++) {
            if (progress(a, i, inventory) < a.definition.objectives().get(i).required()) {
                return false;
            }
        }
        return true;
    }

    // ===== 結束 =====

    /** 交任務：從進行中移除；不可重複的記為已完成。 */
    public void complete(String questId) {
        ActiveQuest a = active.remove(questId);
        if (a != null && !a.definition.repeatable()) {
            completed.add(questId);
        }
        dirty = true;
    }

    public void abandon(String questId) {
        active.remove(questId);
        dirty = true;
    }

    // ===== 查詢 =====

    public Collection<ActiveQuest> active() {
        return Collections.unmodifiableCollection(active.values());
    }

    public Optional<ActiveQuest> active(String questId) {
        return Optional.ofNullable(active.get(questId));
    }

    public boolean isCompleted(String questId) {
        return completed.contains(questId);
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    private static int[] parseKills(String progress, QuestDefinition q) {
        int[] kills = new int[q.objectives().size()];
        if (progress == null || progress.isBlank()) {
            return kills;
        }
        String[] parts = progress.split(",");
        for (int i = 0; i < Math.min(parts.length, kills.length); i++) {
            try {
                kills[i] = Math.max(0, Integer.parseInt(parts[i].strip()));
            } catch (NumberFormatException e) {
                // 壞掉的進度當作 0，不影響其他目標
            }
        }
        return kills;
    }
}
