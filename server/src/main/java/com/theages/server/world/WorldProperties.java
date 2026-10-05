package com.theages.server.world;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("theages.world")
public record WorldProperties(int tickRate, String startingZone, List<ZoneDefinition> zones) {

    public ZoneDefinition startingZoneDefinition() {
        return zones.stream()
            .filter(z -> z.id().equals(startingZone))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("找不到起始區域：" + startingZone));
    }
}
