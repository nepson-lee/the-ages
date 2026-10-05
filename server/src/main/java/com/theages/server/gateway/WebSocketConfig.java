package com.theages.server.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
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

    /** 讓容器在收到過大的訊息時直接斷線，不必先整則讀進記憶體。 */
    @Bean
    ServletServerContainerFactoryBean webSocketContainer(RateLimitProperties limits) {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(limits.maxMessageBytes());
        container.setMaxTextMessageBufferSize(limits.maxMessageBytes());
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
            .addInterceptors(interceptor)
            .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }
}
