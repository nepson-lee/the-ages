package com.theages.server.world;

import com.theages.protocol.v1.CombatEvent;
import com.theages.protocol.v1.TextChannel;
import java.util.ArrayList;
import java.util.random.RandomGenerator;

/**
 * 回合制近戰：每個在戰鬥中的實體每 {@link #roundTicks} 個 tick 出手一次。
 * 不在攻擊距離內時會先追上目標。所有判定都在伺服器上以亂數決定。
 */
final class Combat {

    /** 近戰攻擊距離（公尺）。 */
    static final float MELEE_RANGE = 1.8f;
    /** 追擊時停在目標前方多遠，避免模型重疊。 */
    private static final float CHASE_STOP = 1.4f;

    private final Zone zone;
    private final RandomGenerator random;
    final int roundTicks;

    Combat(Zone zone, RandomGenerator random, int tickRate) {
        this.zone = zone;
        this.random = random;
        this.roundTicks = tickRate; // 一秒一回合
    }

    void update(long tick) {
        // 複製一份：攻擊可能造成死亡，進而從區域移除實體
        for (Entity attacker : new ArrayList<>(zone.entities())) {
            Entity target = attacker.combatTarget();
            if (target == null || attacker.isDead()) {
                continue;
            }
            if (target.isDead() || !zone.contains(target)) {
                attacker.setCombatTarget(null);
                continue;
            }
            float dist = attacker.distanceTo(target);
            if (dist > MELEE_RANGE) {
                float k = (dist - CHASE_STOP) / dist;
                attacker.moveToward(attacker.x() + (target.x() - attacker.x()) * k,
                    attacker.z() + (target.z() - attacker.z()) * k);
                continue;
            }
            attacker.stop();
            if (tick >= attacker.nextAttackTick) {
                attacker.nextAttackTick = tick + roundTicks;
                strike(attacker, target, tick);
            }
        }
    }

    private void strike(Entity attacker, Entity target, long tick) {
        attacker.lastCombatTick = tick;
        target.lastCombatTick = tick;

        boolean hit = random.nextDouble() < hitChance(attacker, target);
        int damage = hit ? rollDamage(attacker, target) : 0;
        if (hit) {
            target.damage(damage);
        }
        boolean killed = target.isDead();

        zone.broadcastCombat(CombatEvent.newBuilder()
            .setAttackerId(attacker.id())
            .setTargetId(target.id())
            .setDamage(damage)
            .setMiss(!hit)
            .setKilled(killed)
            .build());
        describe(attacker, target, hit, damage);

        if (killed) {
            zone.onKilled(target, attacker);
        } else if (target instanceof NpcEntity npc && npc.combatTarget() == null) {
            // 被打的 NPC 會反擊，稍微延遲讓玩家先看到自己的攻擊
            npc.returningHome = false;
            npc.setCombatTarget(attacker);
            npc.nextAttackTick = tick + roundTicks / 2;
        }
    }

    /** 基本命中率 80%，每差一級 ±5%，限制在 30%～95%。 */
    static double hitChance(Entity attacker, Entity target) {
        double chance = 0.80 + (attacker.level() - target.level()) * 0.05;
        return Math.max(0.30, Math.min(0.95, chance));
    }

    /** 傷害 = 攻擊力 × (0.7～1.3) − 防禦力 ÷ 2，至少 1 點。 */
    private int rollDamage(Entity attacker, Entity target) {
        double roll = attacker.attack() * (0.7 + random.nextDouble() * 0.6);
        return Math.max(1, (int) Math.round(roll - target.defense() / 2.0));
    }

    private void describe(Entity attacker, Entity target, boolean hit, int damage) {
        if (attacker instanceof PlayerEntity p) {
            zone.sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, hit
                ? "你攻擊" + target.name() + "，造成 " + damage + " 點傷害。"
                : "你的攻擊被" + target.name() + "閃開了。");
        }
        if (target instanceof PlayerEntity p) {
            String verb = attacker instanceof NpcEntity npc ? npc.template().verb() : "攻擊了";
            zone.sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, hit
                ? attacker.name() + verb + "你，造成 " + damage + " 點傷害。"
                : attacker.name() + "朝你撲來，但被你躲開了。");
        }
    }
}
