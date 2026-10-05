package com.theages.server.world;

import com.theages.protocol.v1.EntityKind;
import com.theages.protocol.v1.EntityState;
import com.theages.protocol.v1.Vec2;
import com.theages.server.world.item.ItemTemplate;

/**
 * 地上的物品。擊殺掉落的戰利品在 owner 期間內只有擊殺者能撿；時間到就消失。
 * 只能在所屬區域的 tick 執行緒存取。
 */
final class GroundItem {

    private final int id;
    private final ItemTemplate template;
    private final int quantity;
    private final float x;
    private final float z;
    /** 擁有者角色 id；0 = 任何人都能撿。 */
    private final long ownerCharacterId;
    private final String ownerName;
    private final long ownerUntilTick;
    private final long despawnAtTick;
    /** 還沒廣播給客戶端。 */
    private boolean announced;

    GroundItem(int id, ItemTemplate template, int quantity, float x, float z,
               long ownerCharacterId, String ownerName, long ownerUntilTick, long despawnAtTick) {
        this.id = id;
        this.template = template;
        this.quantity = quantity;
        this.x = x;
        this.z = z;
        this.ownerCharacterId = ownerCharacterId;
        this.ownerName = ownerName;
        this.ownerUntilTick = ownerUntilTick;
        this.despawnAtTick = despawnAtTick;
    }

    boolean canBeTakenBy(PlayerEntity p, long tick) {
        return ownerCharacterId == 0 || ownerCharacterId == p.characterId() || tick >= ownerUntilTick;
    }

    boolean expired(long tick) {
        return tick >= despawnAtTick;
    }

    boolean consumeUnannounced() {
        boolean fresh = !announced;
        announced = true;
        return fresh;
    }

    String displayName() {
        return quantity > 1 ? template.name() + " ×" + quantity : template.name();
    }

    EntityState toState() {
        return EntityState.newBuilder()
            .setId(id)
            .setName(displayName())
            .setKind(EntityKind.ENTITY_KIND_ITEM)
            .setModel(template.id())
            .setPosition(Vec2.newBuilder().setX(x).setZ(z))
            .build();
    }

    int id() {
        return id;
    }

    ItemTemplate template() {
        return template;
    }

    int quantity() {
        return quantity;
    }

    float x() {
        return x;
    }

    float z() {
        return z;
    }

    long ownerCharacterId() {
        return ownerCharacterId;
    }

    String ownerName() {
        return ownerName;
    }
}
