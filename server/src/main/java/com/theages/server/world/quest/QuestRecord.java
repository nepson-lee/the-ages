package com.theages.server.world.quest;

/**
 * 任務進度的存檔格式（與資料庫的 character_quest 一對一）。
 *
 * @param completed true = 已完成（不可重複的任務才會留下這筆）
 * @param progress  各個擊殺目標的計數，以逗號分隔，例如 "3,0"；收集目標不存（以背包為準）
 */
public record QuestRecord(String questId, boolean completed, String progress) {
}
