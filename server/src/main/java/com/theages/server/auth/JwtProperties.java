package com.theages.server.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("theages.jwt")
public record JwtProperties(String secret, Duration ttl) {
}
