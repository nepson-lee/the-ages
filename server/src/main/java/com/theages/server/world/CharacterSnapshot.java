package com.theages.server.world;

/** 要寫回資料庫的角色狀態。 */
public record CharacterSnapshot(long characterId, String zoneId, float x, float z, int level, int exp, int hp) {
}
