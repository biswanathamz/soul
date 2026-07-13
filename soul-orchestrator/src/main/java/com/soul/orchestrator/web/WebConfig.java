package com.soul.orchestrator.web;

import com.soul.orchestrator.config.SoulProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the UI origin. In the normal setup the browser reaches the orchestrator
 * same-origin via the Vite/nginx proxy, but allowing the configured origin lets the UI
 * hit the API directly too (SPEC §10).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SoulProperties props;

    public WebConfig(SoulProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.getWeb().getCorsAllowedOrigin())
                .allowedMethods("GET", "POST", "PUT", "OPTIONS");
        registry.addMapping("/actuator/**")
                .allowedOrigins(props.getWeb().getCorsAllowedOrigin())
                .allowedMethods("GET");
    }
}
