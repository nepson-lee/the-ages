package com.theages.server.world;

import com.theages.protocol.v1.TextChannel;
import java.util.Comparator;
import java.util.random.RandomGenerator;

/**
 * NPC 的行為：閒晃、主動攻擊、追太遠就放棄回家、死後重生。
 * 戰鬥中的出手與追擊由 {@link Combat} 處理。
 */
final class NpcBrain {

    /** 主動攻擊型 NPC 發現玩家的距離。 */
    static final float AGGRO_RANGE = 6f;
    /** 離出生點超過這個距離就放棄追擊。 */
    static final float LEASH_RANGE = 18f;

    private final Zone zone;
    private final RandomGenerator random;
    private final int tickRate;

    NpcBrain(Zone zone, RandomGenerator random, int tickRate) {
        this.zone = zone;
        this.random = random;
        this.tickRate = tickRate;
    }

    void update(long tick) {
        for (NpcEntity npc : zone.npcs()) {
            if (npc.isDead()) {
                if (tick >= npc.respawnAtTick) {
                    zone.respawn(npc);
                }
            } else if (npc.combatTarget() != null) {
                if (npc.distanceFromHome() > LEASH_RANGE) {
                    npc.goHome();
                }
            } else if (npc.returningHome) {
                if (!npc.isMoving()) {
                    npc.returningHome = false;
                    npc.setHp(npc.maxHp());
                }
            } else if (!(npc.template().aggressive() && tryAggro(npc, tick))) {
                wander(npc, tick);
            }
        }
    }

    private boolean tryAggro(NpcEntity npc, long tick) {
        return zone.players().stream()
            .filter(p -> !p.isDead() && npc.distanceTo(p) <= AGGRO_RANGE)
            .min(Comparator.comparingDouble(npc::distanceTo))
            .map(p -> {
                npc.setCombatTarget(p);
                npc.nextAttackTick = tick + tickRate / 2;
                zone.sendText(p, TextChannel.TEXT_CHANNEL_ROOM, npc.name() + "低吼一聲，朝你衝了過來！");
                return true;
            })
            .orElse(false);
    }

    private void wander(NpcEntity npc, long tick) {
        if (tick < npc.nextWanderTick || npc.isMoving()) {
            return;
        }
        // 每 3～8 秒換一個閒晃地點
        npc.nextWanderTick = tick + tickRate * (3 + random.nextInt(6));
        float r = npc.template().wanderRadius();
        if (r <= 0) {
            return;
        }
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = Math.sqrt(random.nextDouble()) * r;
        ZoneDefinition def = zone.definition();
        npc.moveToward(def.clamp((float) (npc.homeX() + Math.cos(angle) * dist)),
            def.clamp((float) (npc.homeZ() + Math.sin(angle) * dist)));
    }
}
