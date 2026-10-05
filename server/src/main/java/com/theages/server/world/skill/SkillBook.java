package com.theages.server.world.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 玩家學會的技能、冷卻與增益效果。冷卻與增益以區域 tick 計算，不存檔。
 * 只能在玩家所屬區域的 tick 執行緒存取。
 */
public final class SkillBook {

    /** 所有技能共用的冷卻：施放任何技能後這麼多秒內不能再施放。 */
    public static final double GLOBAL_COOLDOWN_SECONDS = 1.0;

    /** 生效中的增益。 */
    public record Buff(SkillDefinition skill, long expiresAtTick) {
    }

    private final Map<String, SkillDefinition> known = new LinkedHashMap<>();
    private final Map<String, Long> readyAtTick = new HashMap<>();
    private final Map<String, Buff> buffs = new LinkedHashMap<>();
    private long globalReadyAtTick;
    private boolean dirty = true;

    /** 從存檔（技能 id 清單）還原。內容檔已刪除的技能會被略過並回報給 onUnknown。 */
    public static SkillBook fromIds(List<String> ids, Map<String, SkillDefinition> skills, Consumer<String> onUnknown) {
        SkillBook book = new SkillBook();
        for (String id : ids) {
            SkillDefinition s = skills.get(id);
            if (s == null) {
                onUnknown.accept(id);
            } else {
                book.known.put(id, s);
            }
        }
        return book;
    }

    public List<String> toIds() {
        return List.copyOf(known.keySet());
    }

    public boolean knows(String skillId) {
        return known.containsKey(skillId);
    }

    public List<SkillDefinition> known() {
        return List.copyOf(known.values());
    }

    public Optional<SkillDefinition> find(String query) {
        return known.values().stream().filter(s -> s.matches(query)).findFirst();
    }

    public void learn(SkillDefinition skill) {
        known.put(skill.id(), skill);
        dirty = true;
    }

    // ===== 冷卻 =====

    /** 還要等幾個 tick 才能施放（含共用冷卻）；0 = 可以施放。 */
    public long ticksUntilReady(SkillDefinition skill, long tick) {
        long ready = Math.max(globalReadyAtTick, readyAtTick.getOrDefault(skill.id(), 0L));
        return Math.max(0, ready - tick);
    }

    public void startCooldown(SkillDefinition skill, long tick, int tickRate) {
        readyAtTick.put(skill.id(), tick + Math.round(skill.cooldownSeconds() * tickRate));
        globalReadyAtTick = tick + Math.round(GLOBAL_COOLDOWN_SECONDS * tickRate);
        dirty = true;
    }

    // ===== 增益 =====

    /** 套上增益；同一個技能重複施放只會刷新持續時間，不會疊加。 */
    public void applyBuff(SkillDefinition skill, long tick, int tickRate) {
        buffs.put(skill.id(), new Buff(skill, tick + (long) skill.durationSeconds() * tickRate));
    }

    /** 移除到期的增益，回傳被移除的。 */
    public List<Buff> expireBuffs(long tick) {
        List<Buff> expired = new ArrayList<>();
        buffs.values().removeIf(b -> {
            if (b.expiresAtTick() <= tick) {
                expired.add(b);
                return true;
            }
            return false;
        });
        return expired;
    }

    public List<Buff> buffs() {
        return Collections.unmodifiableList(new ArrayList<>(buffs.values()));
    }

    public int buffAttack() {
        return buffs.values().stream().mapToInt(b -> b.skill().attack()).sum();
    }

    public int buffDefense() {
        return buffs.values().stream().mapToInt(b -> b.skill().defense()).sum();
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }
}
