package com.theages.server.gateway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("theages.websocket")
public record WebSocketProperties(List<String> allowedOrigins) {
}
