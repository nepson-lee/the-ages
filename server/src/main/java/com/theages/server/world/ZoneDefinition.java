package com.theages.server.world;

/**
 * 區域的靜態資料（之後可改由 YAML/JSON 內容檔載入）。
 *
 * @param size 區域為邊長 size 的正方形，中心在原點
 */
public record ZoneDefinition(String id, String name, String description, float size, Point spawn) {

    public record Point(float x, float z) {
    }

    public float clamp(float v) {
        float half = size / 2f;
        return Math.max(-half, Math.min(half, v));
    }
}
