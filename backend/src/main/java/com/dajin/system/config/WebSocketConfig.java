package com.dajin.system.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final SyncWebSocketHandler handler;
    private final String[] allowedOrigins;
    public WebSocketConfig(SyncWebSocketHandler handler, @Value("${app.cors-origins}") String origins) {
        this.handler = handler;
        this.allowedOrigins = java.util.Arrays.stream(origins.split(",")).map(String::trim).filter(v -> !v.isBlank()).toArray(String[]::new);
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOrigins(allowedOrigins);
    }
}
