package com.example.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ChatSocket socket;
    private final String[] origins;
    public WebSocketConfig(ChatSocket socket, @Value("${chat.allowed-origins}") String[] origins) {
        this.socket = socket; this.origins = origins;
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/ws").setAllowedOrigins(origins);
    }
}
