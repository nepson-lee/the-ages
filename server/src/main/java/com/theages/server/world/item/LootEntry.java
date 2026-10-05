package com.theages.server.world.item;

/**
 * 掉寶表的一列：每次擊殺獨立擲骰。
 *
 * @param chance 0～1 的機率
 * @param min    掉落數量下限（預設 1）
 * @param max    掉落數量上限（預設等於 min）
 */
public record LootEntry(String item, double chance, Integer min, Integer max) {

    public int minOrOne() {
        return min == null ? 1 : min;
    }

    public int maxOrMin() {
        return max == null ? minOrOne() : Math.max(max, minOrOne());
    }
}
