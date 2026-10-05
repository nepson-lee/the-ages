package com.theages.server.world;

import java.util.List;

/**
 * 區域的靜態資料，由 {@code world/content.yml} 載入。
 *
 * @param size 區域為邊長 size 的正方形，中心在原點
 * @param npcs 區域內放置的 NPC
 */
public record ZoneDefinition(String id, String name, String description, float size, Point spawn,
                             List<NpcSpawn> npcs) {

    public ZoneDefinition {
        npcs = npcs == null ? List.of() : List.copyOf(npcs);
    }

    public record Point(float x, float z) {
    }

    /** 在 (x, z) 附近放置 count 隻 template。 */
    public record NpcSpawn(String template, float x, float z, Integer count) {

        public int countOrOne() {
            return count == null ? 1 : count;
        }
    }

    public float clamp(float v) {
        float half = size / 2f;
        return Math.max(-half, Math.min(half, v));
    }
}
