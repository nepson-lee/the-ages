package com.theages.server.world;

import com.theages.protocol.v1.EntityKind;

/** 由模板產生的 NPC。死亡後不會被丟掉，而是等待重生。 */
public final class NpcEntity extends Entity {

    private final NpcTemplate template;
    private final float homeX;
    private final float homeZ;

    /** 脫離戰鬥後正在走回出生點，途中不理會玩家。 */
    boolean returningHome;
    long nextWanderTick;
    /** 死亡時記錄重生的 tick；活著時為 -1。 */
    long respawnAtTick = -1;

    NpcEntity(int id, NpcTemplate template, float homeX, float homeZ) {
        super(id, template.name(), homeX, homeZ);
        this.template = template;
        this.homeX = homeX;
        this.homeZ = homeZ;
        this.level = template.level();
        this.maxHp = template.maxHp();
        this.attack = template.attack();
        this.defense = template.defense();
        setHp(maxHp);
    }

    void respawn() {
        respawnAtTick = -1;
        returningHome = false;
        setCombatTarget(null);
        teleport(homeX, homeZ);
        setHp(maxHp);
    }

    float distanceFromHome() {
        return distanceTo(homeX, homeZ);
    }

    void goHome() {
        setCombatTarget(null);
        returningHome = true;
        moveToward(homeX, homeZ);
    }

    @Override
    EntityKind kind() {
        return template.isMerchant() ? EntityKind.ENTITY_KIND_MERCHANT : EntityKind.ENTITY_KIND_NPC;
    }

    @Override
    String model() {
        return template.id();
    }

    @Override
    float speed() {
        return template.speed();
    }

    public NpcTemplate template() {
        return template;
    }

    float homeX() {
        return homeX;
    }

    float homeZ() {
        return homeZ;
    }
}
