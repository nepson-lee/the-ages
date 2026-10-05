package com.theages.server.world;

import java.util.List;
import java.util.Locale;

/**
 * 區域的靜態資料，由 {@code world/content.yml} 載入。
 *
 * @param size  區域為邊長 size 的正方形，中心在原點
 * @param spawn 出生點，也是在這個區域死亡後的復活點
 * @param npcs  區域內放置的 NPC
 * @param exits 通往其他區域的出口
 */
public record ZoneDefinition(String id, String name, String description, float size, Point spawn,
                             List<NpcSpawn> npcs, List<Exit> exits) {

    public ZoneDefinition {
        npcs = npcs == null ? List.of() : List.copyOf(npcs);
        exits = exits == null ? List.of() : List.copyOf(exits);
    }

    public record Point(float x, float z) {
    }

    /** 在 (x, z) 附近放置 count 隻 template。 */
    public record NpcSpawn(String template, float x, float z, Integer count) {

        public int countOrOne() {
            return count == null ? 1 : count;
        }
    }

    /**
     * 出口：站在 (x, z) 附近時可以前往 to 區域的 (toX, toZ)。
     *
     * @param keywords go 指令可用的代稱；方向詞（north、n……）也能直接當指令打
     */
    public record Exit(String name, List<String> keywords, float x, float z, String to, float toX, float toZ) {

        public Exit {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }

        public boolean matches(String query) {
            String q = query.toLowerCase(Locale.ROOT);
            return name.equals(query) || keywords.stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).equals(q));
        }
    }

    public float clamp(float v) {
        float half = size / 2f;
        return Math.max(-half, Math.min(half, v));
    }

    public boolean contains(float x, float z) {
        float half = size / 2f;
        return Math.abs(x) <= half && Math.abs(z) <= half;
    }
}
