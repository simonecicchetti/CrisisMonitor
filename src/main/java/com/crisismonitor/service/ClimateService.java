package com.crisismonitor.service;

import com.crisismonitor.model.ClimateData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class ClimateService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ClimateService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Cacheable("climateData")
    public List<ClimateData> getClimateAnomalies() {
        log.info("Fetching climate data from HungerMap API...");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.hungermapdata.org/v1/climate/country"))
                    .header("User-Agent", "CrisisMonitor/2.1 (humanitarian-monitoring)")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() == 200) {
                List<ClimateData> result = parseClimateResponse(
                        objectMapper.readTree(httpResponse.body()));
                if (!result.isEmpty()) {
                    log.info("Fetched live climate data for {} countries", result.size());
                    return result;
                }
            } else {
                log.warn("HungerMap API returned status {}", httpResponse.statusCode());
            }
        } catch (Exception e) {
            log.warn("Live HungerMap API failed: {}", e.getMessage());
        }

        log.info("Loading NDVI from static fallback...");
        return loadStaticFallback();
    }

    private List<ClimateData> loadStaticFallback() {
        try {
            InputStream is = new ClassPathResource("data/ndvi-fallback.json").getInputStream();
            JsonNode response = objectMapper.readTree(is);
            List<ClimateData> result = parseClimateResponse(response);
            log.info("Loaded NDVI static fallback: {} countries", result.size());
            return result;
        } catch (Exception e) {
            log.error("Failed to load static NDVI fallback: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ClimateData> parseClimateResponse(JsonNode response) {
        if (response == null || !response.has("body")) {
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

        return result;
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
