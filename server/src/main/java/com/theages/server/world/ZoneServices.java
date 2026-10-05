package com.theages.server.world;

import com.theages.server.world.party.PartyService;
import java.util.function.Function;

/**
 * 區域依賴的外部服務。
 *
 * @param store   角色存檔（非同步）
 * @param parties 組隊（跨區域共用）
 * @param zones   依 id 找其他區域（換區用）
 */
public record ZoneServices(CharacterStore store, PartyService parties, Function<String, Zone> zones) {
}
