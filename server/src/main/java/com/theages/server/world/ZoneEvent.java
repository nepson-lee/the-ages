package com.theages.server.world;

/** 從其他執行緒送進區域的事件，在下一個 tick 開始時依序處理。 */
public sealed interface ZoneEvent {

    PlayerConnection connection();

    record Join(PlayerConnection connection, long characterId, String name, float x, float z) implements ZoneEvent {
    }

    record Leave(PlayerConnection connection) implements ZoneEvent {
    }

    record Move(PlayerConnection connection, float targetX, float targetZ) implements ZoneEvent {
    }

    record CommandText(PlayerConnection connection, String text) implements ZoneEvent {
    }
}
