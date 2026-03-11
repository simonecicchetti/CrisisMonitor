package com.crisismonitor.service;

import com.crisismonitor.model.Headline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ReliefWeb API Service - Official humanitarian intelligence
 *
 * ReliefWeb is OCHA's information service for humanitarian professionals.
 * Reports here are from UN agencies, NGOs, governments - not media speculation.
 *
 * API Docs: https://apidoc.reliefweb.int
 */
@Slf4j
@Service
public class ReliefWebService {

    private static final String BASE_URL = "https://api.reliefweb.int/v2";
    private static final String APPNAME = "simone-reliefwebdata-un8ea";

    // ISO3 to ReliefWeb country ID mapping (ReliefWeb uses numeric IDs)
    // Verified against ReliefWeb API v2 /countries endpoint on 2026-02-11
    private static final Map<String, Integer> COUNTRY_IDS = Map.ofEntries(
            // Africa - Horn of Africa
            Map.entry("SDN", 220),   // Sudan
            Map.entry("SSD", 8657),  // South Sudan
            Map.entry("SOM", 216),   // Somalia
            Map.entry("ETH", 87),    // Ethiopia
            Map.entry("KEN", 131),   // Kenya
            // Africa - Sahel
            Map.entry("NGA", 175),   // Nigeria
            Map.entry("MLI", 149),   // Mali
            Map.entry("NER", 174),   // Niger
            Map.entry("BFA", 46),    // Burkina Faso
            Map.entry("TCD", 55),    // Chad
            Map.entry("CMR", 49),    // Cameroon
            // Africa - Central/Southern
            Map.entry("COD", 75),    // DR Congo
            Map.entry("CAF", 54),    // Central African Republic
            Map.entry("MOZ", 164),   // Mozambique
            // MENA
            Map.entry("YEM", 255),   // Yemen
            Map.entry("SYR", 226),   // Syria
            Map.entry("LBN", 137),   // Lebanon
            Map.entry("IRQ", 122),   // Iraq
            Map.entry("PSE", 180),   // Palestine (oPt)
            Map.entry("LBY", 140),   // Libya
            Map.entry("JOR", 129),   // Jordan
            // LAC (Latin America & Caribbean)
            Map.entry("HTI", 113),   // Haiti
            Map.entry("VEN", 250),   // Venezuela
            Map.entry("COL", 64),    // Colombia
            Map.entry("GTM", 109),   // Guatemala
            Map.entry("HND", 116),   // Honduras
            Map.entry("SLV", 83),    // El Salvador
            Map.entry("MEX", 156),   // Mexico
            Map.entry("NIC", 173),   // Nicaragua
            Map.entry("PER", 187),   // Peru
            Map.entry("ECU", 81),    // Ecuador
            Map.entry("CUB", 71),    // Cuba
            Map.entry("PAN", 184),   // Panama
            // Asia
            Map.entry("AFG", 13),    // Afghanistan
            Map.entry("MMR", 165),   // Myanmar
            Map.entry("PAK", 182),   // Pakistan
            Map.entry("BGD", 31),    // Bangladesh
            Map.entry("NPL", 168),   // Nepal
            Map.entry("PHL", 188),   // Philippines
            // Europe
            Map.entry("UKR", 241),   // Ukraine
            // North America
            Map.entry("USA", 245),   // United States
            Map.entry("CAN", 50)     // Canada
    );

    private final WebClient webClient = WebClient.builder()
            .baseUrl(BASE_URL)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Get latest humanitarian reports for a country.
     * Returns official reports from UN, NGOs, governments.
     * Uses preset=latest which returns most recent reports by date.
     *
     * @param iso3 Country ISO3 code
     * @param limit Maximum number of reports
     * @param days Kept for API compatibility (not used - preset=latest handles recency)
     */
    @Cacheable(value = "reliefwebReports", key = "#iso3 + '-' + #limit + '-' + #days")
    public List<HumanitarianReport> getLatestReports(String iso3, int limit, int days) {
        Integer countryId = COUNTRY_IDS.get(iso3.toUpperCase());
        if (countryId == null) {
            log.debug("No ReliefWeb country ID mapping for {}", iso3);
            return Collections.emptyList();
        }

        log.info("Fetching {} ReliefWeb reports for {} (ID: {})", limit, iso3, countryId);

        try {
            // Use GET with simple country filter + preset=latest
            // preset=latest returns reports sorted by date descending
            String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/reports")
                    .queryParam("appname", APPNAME)
                    .queryParam("limit", limit)
                    .queryParam("preset", "latest")
                    .queryParam("filter[field]", "primary_country.id")
                    .queryParam("filter[value]", countryId)
                    .queryParam("fields[include][]", "title")
                    .queryParam("fields[include][]", "source.shortname")
                    .queryParam("fields[include][]", "date.original")
                    .queryParam("fields[include][]", "url")
                    .queryParam("fields[include][]", "theme.name")
                    .queryParam("fields[include][]", "format.name")
                    .build()
                    .toUriString();

            String responseStr = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseStr == null || responseStr.isBlank()) {
                return Collections.emptyList();
            }

            return parseReportsResponse(responseStr);

        } catch (Exception e) {
            log.error("Error fetching ReliefWeb reports for {}: {}", iso3, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Backward-compatible overload (defaults to 30 days)
     */
    public List<HumanitarianReport> getLatestReports(String iso3, int limit) {
        return getLatestReports(iso3, limit, 30);
    }

    /**
     * Get latest reports as Headline objects (for News Signal integration).
     */
    @Cacheable(value = "reliefwebHeadlines", key = "#iso3 + '-' + #limit + '-' + #days")
    public List<Headline> getLatestReportsAsHeadlines(String iso3, int limit, int days) {
        List<HumanitarianReport> reports = getLatestReports(iso3, limit, days);

        return reports.stream()
                .map(r -> Headline.builder()
                        .title(truncateTitle(r.getTitle(), 80))
                        .url(r.getUrl())
                        .source(r.getSource())
                        .build())
                .toList();
    }

    /**
     * Backward-compatible overload (defaults to 30 days)
     */
    public List<Headline> getLatestReportsAsHeadlines(String iso3, int limit) {
        return getLatestReportsAsHeadlines(iso3, limit, 30);
    }

    /**
     * Get active disasters for a country.
     */
    @Cacheable(value = "reliefwebDisasters", key = "#iso3")
    public List<DisasterInfo> getActiveDisasters(String iso3) {
        Integer countryId = COUNTRY_IDS.get(iso3.toUpperCase());
        if (countryId == null) {
            return Collections.emptyList();
        }

        log.info("Fetching active disasters for {} (ID: {})", iso3, countryId);

        try {
            String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/disasters")
                    .queryParam("appname", APPNAME)
                    .queryParam("filter[field]", "primary_country.id")
                    .queryParam("filter[value]", countryId)
                    .queryParam("filter[field]", "status")
                    .queryParam("filter[value]", "current")
                    .queryParam("limit", 5)
                    .queryParam("fields[include][]", "name")
                    .queryParam("fields[include][]", "type.name")
                    .queryParam("fields[include][]", "date.event")
                    .queryParam("fields[include][]", "url")
                    .build()
                    .toUriString();

            String responseStr = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseStr == null || responseStr.isBlank()) {
                return Collections.emptyList();
            }

            return parseDisastersResponse(responseStr);

        } catch (Exception e) {
            log.error("Error fetching disasters for {}: {}", iso3, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<HumanitarianReport> parseReportsResponse(String responseStr) {
        List<HumanitarianReport> reports = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseStr);
            JsonNode data = root.path("data");

            if (data.isArray()) {
                for (JsonNode item : data) {
                    JsonNode fields = item.path("fields");

                    String title = fields.path("title").asText(null);
                    String url = fields.path("url").asText(null);
                    String date = fields.path("date").path("original").asText(null);

                    // Source can be array or object
                    String source = null;
                    JsonNode sourceNode = fields.path("source");
                    if (sourceNode.isArray() && sourceNode.size() > 0) {
                        source = sourceNode.get(0).path("shortname").asText(null);
                    } else if (sourceNode.isObject()) {
                        source = sourceNode.path("shortname").asText(null);
                    }

                    String country = fields.path("primary_country").path("iso3").asText(null);

                    // Parse themes array
                    List<String> themes = new ArrayList<>();
                    JsonNode themeNode = fields.path("theme");
                    if (themeNode.isArray()) {
                        for (JsonNode t : themeNode) {
                            String themeName = t.path("name").asText(null);
                            if (themeName != null) themes.add(themeName);
                        }
                    }

                    // Parse format
                    String format = null;
                    JsonNode formatNode = fields.path("format");
                    if (formatNode.isArray() && formatNode.size() > 0) {
                        format = formatNode.get(0).path("name").asText(null);
                    }

                    if (title != null && url != null) {
                        reports.add(HumanitarianReport.builder()
                                .title(title)
                                .url(url)
                                .source(source != null ? source : "ReliefWeb")
                                .date(date)
                                .countryIso3(country)
                                .themes(themes)
                                .format(format)
                                .build());
                    }
                }
            }

            log.info("Parsed {} reports from ReliefWeb", reports.size());

        } catch (Exception e) {
            log.error("Error parsing ReliefWeb response: {}", e.getMessage());
        }

        return reports;
    }

    private List<DisasterInfo> parseDisastersResponse(String responseStr) {
        List<DisasterInfo> disasters = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseStr);
            JsonNode data = root.path("data");

            if (data.isArray()) {
                for (JsonNode item : data) {
                    JsonNode fields = item.path("fields");

                    String name = fields.path("name").asText(null);
                    String type = fields.path("type").path("name").asText(null);
                    String date = fields.path("date").path("event").asText(null);
                    String url = fields.path("url").asText(null);

                    if (name != null) {
                        disasters.add(DisasterInfo.builder()
                                .name(name)
                                .type(type)
                                .eventDate(date)
                                .url(url)
                                .build());
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error parsing disasters response: {}", e.getMessage());
        }

        return disasters;
    }

    private String truncateTitle(String title, int maxLength) {
        if (title == null) return null;
        if (title.length() <= maxLength) return title;
        return title.substring(0, maxLength - 3) + "...";
    }

    /**
     * Humanitarian Report from ReliefWeb
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HumanitarianReport {
        private String title;
        private String url;
        private String source;      // Organization shortname (OCHA, WFP, ICRC, etc.)
        private String date;
        private String countryIso3;
        private List<String> themes;   // Food Security, Health, Protection, etc.
        private String format;         // Situation Report, Flash Update, Assessment, etc.
    }

    /**
     * Disaster information from ReliefWeb
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DisasterInfo {
        private String name;
        private String type;        // Flood, Drought, Conflict, etc.
        private String eventDate;
        private String url;
    }
}
