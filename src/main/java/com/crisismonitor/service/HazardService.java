package com.crisismonitor.service;

import com.crisismonitor.model.Hazard;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HazardService {

    private final WebClient pdcClient;

    @Cacheable("hazards")
    public List<Hazard> getActiveHazards() {
        log.info("Fetching active hazards from PDC API...");

        try {
            String uri = "/msf/rest/services/global/pdc_active_hazards/MapServer/1/query" +
                    "?f=json&where=(1%3D1)&returnGeometry=false&outFields=*&resultRecordCount=500";

            JsonNode response = pdcClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("features")) {
                log.error("Invalid response from PDC API");
                return Collections.emptyList();
            }

            JsonNode features = response.get("features");
            List<Hazard> result = new ArrayList<>();

            for (JsonNode feature : features) {
                JsonNode attrs = feature.get("attributes");

                // Skip exercises and inactive hazards
                String category = attrs.has("category_id") ? attrs.get("category_id").asText() : "";
                String status = attrs.has("status") ? attrs.get("status").asText() : "";

                if ("EXERCISE".equals(category) || !"A".equals(status)) {
                    continue;
                }

                Hazard hazard = Hazard.builder()
                        .name(attrs.get("hazard_name").asText())
                        .type(attrs.has("type") ? attrs.get("type").asText() : null)
                        .severity(attrs.has("severity_id") ? attrs.get("severity_id").asText() : null)
                        .category(category)
                        .latitude(attrs.has("latitude") ? attrs.get("latitude").asDouble() : null)
                        .longitude(attrs.has("longitude") ? attrs.get("longitude").asDouble() : null)
                        .createDate(parseTimestamp(attrs.get("create_date")))
                        .lastUpdate(parseTimestamp(attrs.get("last_update")))
                        .status(status)
                        .build();

                result.add(hazard);
            }

            log.info("Fetched {} active hazards", result.size());
            return result;

        } catch (Exception e) {
            log.error("Error fetching hazards: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private LocalDateTime parseTimestamp(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            long millis = node.asLong();
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }
}
