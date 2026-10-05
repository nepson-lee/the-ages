package com.theages.server.world;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 管理所有區域的生命週期。Spring 只負責啟動與關閉，遊戲邏輯都在 {@link Zone} 內。 */
@Component
public class World implements SmartLifecycle {

    private final WorldProperties properties;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private volatile boolean running;

    public World(WorldProperties properties, CharacterStore store) {
        this.properties = properties;
        properties.startingZoneDefinition(); // 啟動時就驗證設定
        WorldContent content = properties.validatedContent();
        AtomicInteger entityIds = new AtomicInteger(1);
        SplittableRandom seeds = new SplittableRandom();
        for (ZoneDefinition def : properties.zones()) {
            // 每個區域各自一個亂數產生器：只在自己的 tick 執行緒上使用，不需同步
            zones.put(def.id(), new Zone(def, content, properties.tickRate(), entityIds::getAndIncrement,
                store, seeds.split(), zones::get));
        }
    }

    /** 找不到（例如區域被移除）時回到起始區域。 */
    public Zone zoneOrStart(String zoneId) {
        Zone zone = zones.get(zoneId);
        return zone != null ? zone : zones.get(properties.startingZone());
    }

    @Override
    public void start() {
        zones.values().forEach(Zone::start);
        running = true;
    }

    @Override
    public void stop() {
        zones.values().forEach(Zone::stop);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
