package com.theages.server.world;

/** 角色存檔。實作必須是非同步的：tick 執行緒不能等資料庫。 */
public interface CharacterStore {

    void saveAsync(long characterId, String zoneId, float x, float z);
}
