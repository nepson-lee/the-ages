package com.theages.server.world;

import com.theages.server.world.item.ItemRecord;
import java.util.List;

/** 從其他執行緒送進區域的事件，在下一個 tick 開始時依序處理。 */
public sealed interface ZoneEvent {

    PlayerConnection connection();

    record Join(PlayerConnection connection, long characterId, String name, float x, float z,
                int level, int exp, int hp, List<ItemRecord> items) implements ZoneEvent {
    }

    record Leave(PlayerConnection connection) implements ZoneEvent {
    }

    record Move(PlayerConnection connection, float targetX, float targetZ) implements ZoneEvent {
    }

    record Attack(PlayerConnection connection, int targetId) implements ZoneEvent {
    }

    record CommandText(PlayerConnection connection, String text) implements ZoneEvent {
    }
}
