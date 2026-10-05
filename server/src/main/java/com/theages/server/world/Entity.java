package com.theages.server.world;

import com.theages.protocol.v1.EntityKind;
import com.theages.protocol.v1.EntityState;
import com.theages.protocol.v1.Vec2;

/** 區域內會移動、會戰鬥的東西。只能在所屬區域的 tick 執行緒存取。 */
public abstract sealed class Entity permits PlayerEntity, NpcEntity {

    private final int id;
    private final String name;
    private float x;
    private float z;
    private float destX;
    private float destZ;
    private boolean dirty = true;
    private boolean statsDirty = true;

    protected int level;
    protected int maxHp;
    protected int attack;
    protected int defense;
    private int hp;

    /** 目前攻擊的對象；null = 不在攻擊。 */
    private Entity combatTarget;
    long nextAttackTick;
    /** 最近一次攻擊或被攻擊的 tick，用來判斷能否回血。 */
    long lastCombatTick = Long.MIN_VALUE / 2;

    protected Entity(int id, String name, float x, float z) {
        this.id = id;
        this.name = name;
        this.x = this.destX = x;
        this.z = this.destZ = z;
    }

    abstract EntityKind kind();

    abstract String model();

    /** 移動速度（公尺/秒）。 */
    abstract float speed();

    // ===== 移動 =====

    void moveToward(float tx, float tz) {
        destX = tx;
        destZ = tz;
    }

    void stop() {
        destX = x;
        destZ = z;
    }

    /** 瞬間移動（重生），不經過中間位置。 */
    void teleport(float tx, float tz) {
        x = destX = tx;
        z = destZ = tz;
        dirty = true;
    }

    boolean isMoving() {
        return distanceTo(destX, destZ) > 1e-4f;
    }

    /** 朝目的地移動一個 tick；有移動時標記為 dirty。 */
    void step(float dt) {
        float dx = destX - x;
        float dz = destZ - z;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        if (dist < 1e-4f) {
            return;
        }
        float maxStep = speed() * dt;
        if (dist <= maxStep) {
            x = destX;
            z = destZ;
        } else {
            x += dx / dist * maxStep;
            z += dz / dist * maxStep;
        }
        dirty = true;
    }

    float distanceTo(Entity other) {
        return distanceTo(other.x, other.z);
    }

    float distanceTo(float px, float pz) {
        float dx = px - x;
        float dz = pz - z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    // ===== 戰鬥 =====

    Entity combatTarget() {
        return combatTarget;
    }

    void setCombatTarget(Entity target) {
        if (combatTarget != target) {
            combatTarget = target;
            dirty = true;
        }
    }

    // ===== 生命 =====

    boolean isDead() {
        return hp <= 0;
    }

    void damage(int amount) {
        setHp(hp - amount);
    }

    void heal(int amount) {
        setHp(hp + amount);
    }

    void setHp(int value) {
        int clamped = Math.max(0, Math.min(maxHp, value));
        if (clamped != hp) {
            hp = clamped;
            dirty = true;
            statsDirty = true;
        }
    }

    void markStatsDirty() {
        statsDirty = true;
        dirty = true;
    }

    boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    boolean consumeStatsDirty() {
        boolean d = statsDirty;
        statsDirty = false;
        return d;
    }

    EntityState toState() {
        return EntityState.newBuilder()
            .setId(id)
            .setName(name)
            .setKind(kind())
            .setModel(model())
            .setPosition(Vec2.newBuilder().setX(x).setZ(z))
            .setLevel(level)
            .setHp(hp)
            .setMaxHp(maxHp)
            .setTargetId(combatTarget == null ? 0 : combatTarget.id)
            .build();
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public float x() {
        return x;
    }

    public float z() {
        return z;
    }

    public int hp() {
        return hp;
    }

    public int maxHp() {
        return maxHp;
    }

    public int level() {
        return level;
    }

    public int attack() {
        return attack;
    }

    public int defense() {
        return defense;
    }
}
