package com.theages.server.world;

import com.theages.protocol.v1.EntityKind;
import com.theages.protocol.v1.EntityState;
import com.theages.protocol.v1.Vec2;

/** 區域內的玩家。只能在所屬區域的 tick 執行緒存取。 */
public final class PlayerEntity {

    /** 移動速度（公尺/秒）。 */
    static final float SPEED = 5f;

    private final int id;
    private final long characterId;
    private final String name;
    private PlayerConnection connection;
    private float x;
    private float z;
    private float targetX;
    private float targetZ;
    private boolean dirty = true;

    PlayerEntity(int id, long characterId, String name, PlayerConnection connection, float x, float z) {
        this.id = id;
        this.characterId = characterId;
        this.name = name;
        this.connection = connection;
        this.x = this.targetX = x;
        this.z = this.targetZ = z;
    }

    void setTarget(float tx, float tz) {
        targetX = tx;
        targetZ = tz;
    }

    /** 朝目標移動一個 tick；有移動時標記為 dirty。 */
    void step(float dt) {
        float dx = targetX - x;
        float dz = targetZ - z;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        if (dist < 1e-4f) {
            return;
        }
        float maxStep = SPEED * dt;
        if (dist <= maxStep) {
            x = targetX;
            z = targetZ;
        } else {
            x += dx / dist * maxStep;
            z += dz / dist * maxStep;
        }
        dirty = true;
    }

    boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    EntityState toState() {
        return EntityState.newBuilder()
            .setId(id)
            .setName(name)
            .setKind(EntityKind.ENTITY_KIND_PLAYER)
            .setPosition(Vec2.newBuilder().setX(x).setZ(z))
            .build();
    }

    void replaceConnection(PlayerConnection newConnection) {
        this.connection = newConnection;
        this.targetX = x;
        this.targetZ = z;
    }

    public int id() {
        return id;
    }

    public long characterId() {
        return characterId;
    }

    public String name() {
        return name;
    }

    public PlayerConnection connection() {
        return connection;
    }

    public float x() {
        return x;
    }

    public float z() {
        return z;
    }
}
