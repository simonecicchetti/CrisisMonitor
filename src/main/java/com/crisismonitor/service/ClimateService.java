package com.crisismonitor.service;

import com.crisismonitor.model.ClimateData;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClimateService {

    private final WebClient hungerMapClient;

    @Cacheable("climateData")
    public List<ClimateData> getClimateAnomalies() {
        log.info("Fetching climate data from HungerMap API...");

        try {
            JsonNode response = hungerMapClient.get()
                    .uri("/v1/climate/country")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("body")) {
                log.error("Invalid response from climate API");
                return Collections.emptyList();
            }

            JsonNode countries = response.get("body").get("countries");
            List<ClimateData> result = new ArrayList<>();

            for (JsonNode node : countries) {
                JsonNode countryNode = node.get("country");

                Double ndvi = node.has("minNdviAnomaly") && !node.get("minNdviAnomaly").isNull()
                        ? node.get("minNdviAnomaly").asDouble() : null;

                ClimateData data = ClimateData.builder()
                        .iso3(countryNode.get("iso3").asText())
                        .name(countryNode.get("name").asText())
                        .ndviAnomaly(ndvi)
                        .date(node.has("date") ? LocalDate.parse(node.get("date").asText()) : null)
                        .alertLevel(ClimateData.computeAlertLevel(ndvi))
                        .build();

                result.add(data);
            }

            log.info("Fetched climate data for {} countries", result.size());
            return result;

        } catch (Exception e) {
            log.error("Error fetching climate data: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get countries with climate stress (NDVI < 0.9)
     */
    public List<ClimateData> getCountriesWithClimateStress() {
        return getClimateAnomalies().stream()
                .filter(c -> c.getNdviAnomaly() != null && c.getNdviAnomaly() < 0.9)
                .sorted((a, b) -> Double.compare(
                        a.getNdviAnomaly() != null ? a.getNdviAnomaly() : 1.0,
                        b.getNdviAnomaly() != null ? b.getNdviAnomaly() : 1.0
                ))
                .toList();
    }
}
