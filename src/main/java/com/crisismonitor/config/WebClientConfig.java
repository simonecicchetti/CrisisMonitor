package com.crisismonitor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024)); // 10MB buffer for large responses
    }

    @Bean
    public WebClient hungerMapClient(WebClient.Builder builder) {
        return builder.clone()
                .baseUrl("https://api.hungermapdata.org")
                .defaultHeader("User-Agent", "CrisisMonitor/2.1 (humanitarian-monitoring)")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Bean
    public WebClient pdcClient(WebClient.Builder builder) {
        return builder.clone()
                .baseUrl("https://apps.pdc.org")
                .build();
    }
}
