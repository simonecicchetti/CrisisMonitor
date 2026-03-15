package com.crisismonitor.service;

import com.crisismonitor.config.MonitoredCountries;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WHO Disease Outbreak News (DON) Service
 *
 * Fetches disease outbreak alerts from WHO's OData API.
 * No API key required.
 *
 * API: https://www.who.int/api/news/diseaseoutbreaknews
 */
@Slf4j
@Service
public class WHODiseaseOutbreakService {

    private static final String BASE_URL = "https://www.who.int/api/news/diseaseoutbreaknews";
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WHODiseaseOutbreakService() {
        // WHO API returns large HTTP headers (cookies/tracking) — increase Netty limit
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                .responseTimeout(Duration.ofSeconds(15))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .httpResponseDecoder(spec -> spec.maxHeaderSize(16384));

        this.webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
    }

    /**
     * Get recent disease outbreak news (last 90 days, max 30 items).
     */
    @Cacheable(value = "whoDiseaseOutbreaks", unless = "#result == null || #result.isEmpty()")
    public List<DiseaseOutbreak> getRecentOutbreaks() {
        log.info("Fetching WHO Disease Outbreak News...");
        try {
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .queryParam("$top", 30)
                            .queryParam("$orderby", "PublicationDate desc")
                            .queryParam("$select", "Id,DonId,Title,PublicationDate,Summary")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            if (response == null) return Collections.emptyList();

            JsonNode root = objectMapper.readTree(response);
            JsonNode value = root.path("value");
            if (!value.isArray()) return Collections.emptyList();

            List<DiseaseOutbreak> outbreaks = new ArrayList<>();
            for (JsonNode node : value) {
                try {
                    DiseaseOutbreak outbreak = parseOutbreak(node);
                    if (outbreak != null) {
                        outbreaks.add(outbreak);
                    }
                } catch (Exception e) {
                    log.debug("Skipping malformed DON entry: {}", e.getMessage());
                }
            }

            log.info("Fetched {} WHO DON entries", outbreaks.size());
            return outbreaks;

        } catch (Exception e) {
            log.warn("Failed to fetch WHO DON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get outbreaks for a specific country (by ISO3 code).
     */
    public List<DiseaseOutbreak> getOutbreaksForCountry(String iso3) {
        String countryName = MonitoredCountries.getName(iso3);
        if (countryName == null || countryName.equals(iso3)) return Collections.emptyList();

        return getRecentOutbreaks().stream()
                .filter(o -> o.getCountryIso3() != null && o.getCountryIso3().equals(iso3))
                .collect(Collectors.toList());
    }

    /**
     * Get outbreak count by country (for dashboard summary).
     */
    public Map<String, Integer> getOutbreakCountsByCountry() {
        return getRecentOutbreaks().stream()
                .filter(o -> o.getCountryIso3() != null)
                .collect(Collectors.groupingBy(
                        DiseaseOutbreak::getCountryIso3,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
    }

    private DiseaseOutbreak parseOutbreak(JsonNode node) {
        String title = node.path("Title").asText(null);
        if (title == null || title.isBlank()) return null;

        String donId = node.path("DonId").asText(null);
        String pubDateStr = node.path("PublicationDate").asText(null);
        String summary = node.path("Summary").asText("");

        // Strip HTML tags from summary
        summary = summary.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (summary.length() > 300) {
            summary = summary.substring(0, 297) + "...";
        }

        // Parse date
        String publishedDate = null;
        String timeAgo = null;
        if (pubDateStr != null) {
            try {
                OffsetDateTime odt = OffsetDateTime.parse(pubDateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                publishedDate = odt.toLocalDate().toString();
                long daysAgo = java.time.temporal.ChronoUnit.DAYS.between(odt.toLocalDate(), java.time.LocalDate.now());
                if (daysAgo == 0) timeAgo = "today";
                else if (daysAgo == 1) timeAgo = "1d ago";
                else timeAgo = daysAgo + "d ago";
            } catch (Exception e) {
                log.debug("Could not parse date: {}", pubDateStr);
            }
        }

        // Extract disease and country from title (format: "Disease - Country")
        String disease = null;
        String country = null;
        String countryIso3 = null;

        if (title.contains(" - ")) {
            String[] parts = title.split(" - ", 2);
            disease = parts[0].trim();
            country = parts[1].trim();
        } else {
            disease = title;
        }

        // Map country name to ISO3
        if (country != null) {
            countryIso3 = matchCountryToIso3(country);
        }

        String url = donId != null
                ? "https://www.who.int/emergencies/disease-outbreak-news/item/" + donId
                : null;

        return DiseaseOutbreak.builder()
                .donId(donId)
                .title(title)
                .disease(disease)
                .country(country)
                .countryIso3(countryIso3)
                .summary(summary)
                .publishedDate(publishedDate)
                .timeAgo(timeAgo)
                .url(url)
                .build();
    }

    private String matchCountryToIso3(String countryName) {
        if (countryName == null) return null;
        String lower = countryName.toLowerCase().trim();

        // Try exact match from MonitoredCountries
        for (Map.Entry<String, String> entry : MonitoredCountries.COUNTRY_NAMES.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(countryName)) {
                return entry.getKey();
            }
        }

        // Try alias matching
        for (Map.Entry<String, List<String>> entry : MonitoredCountries.COUNTRY_ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                if (lower.contains(alias)) {
                    return entry.getKey();
                }
            }
        }

        return null;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiseaseOutbreak {
        private String donId;
        private String title;
        private String disease;
        private String country;
        private String countryIso3;
        private String summary;
        private String publishedDate;
        private String timeAgo;
        private String url;
    }
}
