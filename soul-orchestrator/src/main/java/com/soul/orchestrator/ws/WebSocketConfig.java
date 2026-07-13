package com.soul.orchestrator.ws;

import com.soul.orchestrator.config.SoulProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the streaming handler at {@code /ws/stream}. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final StreamWebSocketHandler handler;
    private final SoulProperties props;

    public WebSocketConfig(StreamWebSocketHandler handler, SoulProperties props) {
        this.handler = handler;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/stream")
                .setAllowedOrigins(props.getWeb().getCorsAllowedOrigin());
    }
}
