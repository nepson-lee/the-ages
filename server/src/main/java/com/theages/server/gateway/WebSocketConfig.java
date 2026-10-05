package com.theages.server.gateway;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameWebSocketHandler handler;
    private final JwtHandshakeInterceptor interceptor;
    private final WebSocketProperties properties;

    public WebSocketConfig(GameWebSocketHandler handler, JwtHandshakeInterceptor interceptor,
                           WebSocketProperties properties) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
            .addInterceptors(interceptor)
            .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }
}
