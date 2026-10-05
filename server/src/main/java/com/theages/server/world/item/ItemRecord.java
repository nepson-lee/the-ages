package com.theages.server.world.item;

/** 背包一格的存檔格式（與資料庫的 character_item 一對一）。 */
public record ItemRecord(String templateId, int quantity, boolean equipped) {
}
