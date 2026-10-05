package com.theages.server.world;

import com.theages.protocol.v1.EntityKind;
import com.theages.protocol.v1.EntityState;
import com.theages.protocol.v1.Vec2;

/** 區域出口在場景中的實體。位置固定，只在完整快照中送出。 */
record Portal(int id, ZoneDefinition.Exit exit) {

    EntityState toState() {
        return EntityState.newBuilder()
            .setId(id)
            .setName(exit.name())
            .setKind(EntityKind.ENTITY_KIND_PORTAL)
            .setModel("portal")
            .setPosition(Vec2.newBuilder().setX(exit.x()).setZ(exit.z()))
            .build();
    }
}
