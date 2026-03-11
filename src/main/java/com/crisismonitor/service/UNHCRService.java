package com.crisismonitor.service;

import com.crisismonitor.model.MobilityFlow;
import com.crisismonitor.model.MobilityStock;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for fetching refugee and displacement data from UNHCR API
 * API Docs: https://api.unhcr.org/
 */
@Slf4j
@Service
public class UNHCRService {

    private final WebClient unhcrClient;

    // Major countries of origin for focused queries
    private static final List<String> MAJOR_ORIGINS = List.of(
            "AFG", "SYR", "UKR", "VEN", "SSD", "MMR", "COD", "SOM", "SDN", "CAF",
            "ERI", "NGA", "IRQ", "YEM", "ETH", "HTI", "COL", "PAK", "BGD", "MLI"
    );

    public UNHCRService() {
        this.unhcrClient = WebClient.builder()
                .baseUrl("https://api.unhcr.org/population/v1")
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    /**
     * Get displacement stocks for major countries of origin
     */
    @Cacheable("unhcrPopulation")
    public List<MobilityStock> getDisplacementByOrigin() {
        log.info("Fetching UNHCR population data for major countries of origin...");

        try {
            String countryCodes = String.join(",", MAJOR_ORIGINS);
            JsonNode response = unhcrClient.get()
                    .uri("/population/?year=2023&coo={countries}&limit=50", countryCodes)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("items")) {
                log.error("Invalid response from UNHCR Population API");
                return Collections.emptyList();
            }

            List<MobilityStock> stocks = new ArrayList<>();
            for (JsonNode item : response.get("items")) {
                if (!item.has("coo") || "-".equals(item.get("coo").asText())) {
                    continue;
                }

                MobilityStock stock = MobilityStock.builder()
                        .iso3(item.get("coo").asText())
                        .countryName(item.has("coo_name") ? item.get("coo_name").asText() : null)
                        .year(item.has("year") ? item.get("year").asInt() : 2023)
                        .refugees(parseLong(item, "refugees"))
                        .asylumSeekers(parseLong(item, "asylum_seekers"))
                        .idps(parseLong(item, "idps"))
                        .returnedRefugees(parseLong(item, "returned_refugees"))
                        .returnedIdps(parseLong(item, "returned_idps"))
                        .stateless(parseLong(item, "stateless"))
                        .otherConcern(parseLong(item, "ooc"))
                        .source("UNHCR")
                        .adminLevel("ADMIN0")
                        .build();

                stock.computeTotalDisplaced();
                stocks.add(stock);
            }

            // Sort by total displaced descending
            stocks.sort((a, b) -> Long.compare(
                    b.getTotalDisplaced() != null ? b.getTotalDisplaced() : 0,
                    a.getTotalDisplaced() != null ? a.getTotalDisplaced() : 0
            ));

            log.info("Fetched UNHCR data for {} countries", stocks.size());
            return stocks;

        } catch (Exception e) {
            log.error("Error fetching UNHCR population data: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get global displacement summary
     */
    @Cacheable("unhcrGlobalSummary")
    public MobilityStock getGlobalSummary() {
        log.info("Fetching UNHCR global summary...");

        try {
            JsonNode response = unhcrClient.get()
                    .uri("/population/?year=2023&limit=1")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("items") || response.get("items").isEmpty()) {
                return null;
            }

            JsonNode item = response.get("items").get(0);
            MobilityStock global = MobilityStock.builder()
                    .iso3("GLOBAL")
                    .countryName("Global")
                    .year(2023)
                    .refugees(parseLong(item, "refugees"))
                    .asylumSeekers(parseLong(item, "asylum_seekers"))
                    .idps(parseLong(item, "idps"))
                    .returnedRefugees(parseLong(item, "returned_refugees"))
                    .returnedIdps(parseLong(item, "returned_idps"))
                    .stateless(parseLong(item, "stateless"))
                    .source("UNHCR")
                    .build();

            global.computeTotalDisplaced();
            log.info("Global displacement: {} million", global.getTotalDisplaced() / 1_000_000);
            return global;

        } catch (Exception e) {
            log.error("Error fetching UNHCR global summary: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get asylum applications by origin country
     */
    @Cacheable("unhcrAsylum")
    public List<MobilityFlow> getAsylumApplications() {
        log.info("Fetching UNHCR asylum applications...");

        try {
            String countryCodes = String.join(",", MAJOR_ORIGINS);
            JsonNode response = unhcrClient.get()
                    .uri("/asylum-applications/?year=2023&coo={countries}&limit=100", countryCodes)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("items")) {
                log.error("Invalid response from UNHCR Asylum API");
                return Collections.emptyList();
            }

            List<MobilityFlow> flows = new ArrayList<>();
            for (JsonNode item : response.get("items")) {
                String coo = item.has("coo") ? item.get("coo").asText() : "-";
                String coa = item.has("coa") ? item.get("coa").asText() : "-";

                // Skip aggregated rows
                if ("-".equals(coo) && "-".equals(coa)) continue;

                MobilityFlow flow = MobilityFlow.builder()
                        .originIso3(coo)
                        .originName(item.has("coo_name") ? item.get("coo_name").asText() : null)
                        .destinationIso3(coa)
                        .destinationName(item.has("coa_name") ? item.get("coa_name").asText() : null)
                        .year(item.has("year") ? item.get("year").asInt() : 2023)
                        .count(parseLong(item, "applied"))
                        .flowType("ASYLUM_APPLICATION")
                        .procedureType(item.has("procedure_type") ? item.get("procedure_type").asText() : null)
                        .appType(item.has("app_type") ? item.get("app_type").asText() : null)
                        .source("UNHCR")
                        .build();

                if (flow.getCount() != null && flow.getCount() > 0) {
                    flows.add(flow);
                }
            }

            // Aggregate by origin country
            Map<String, MobilityFlow> aggregated = new HashMap<>();
            for (MobilityFlow flow : flows) {
                String key = flow.getOriginIso3();
                if (aggregated.containsKey(key)) {
                    MobilityFlow existing = aggregated.get(key);
                    existing.setCount(existing.getCount() + flow.getCount());
                } else {
                    aggregated.put(key, MobilityFlow.builder()
                            .originIso3(flow.getOriginIso3())
                            .originName(flow.getOriginName())
                            .year(flow.getYear())
                            .count(flow.getCount())
                            .flowType("ASYLUM_APPLICATION")
                            .source("UNHCR")
                            .build());
                }
            }

            List<MobilityFlow> result = new ArrayList<>(aggregated.values());
            result.sort((a, b) -> Long.compare(b.getCount(), a.getCount()));

            log.info("Fetched {} asylum flow origins", result.size());
            return result;

        } catch (Exception e) {
            log.error("Error fetching UNHCR asylum data: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get demographics breakdown
     */
    @Cacheable("unhcrDemographics")
    public Map<String, Object> getDemographics() {
        log.info("Fetching UNHCR demographics...");

        try {
            JsonNode response = unhcrClient.get()
                    .uri("/demographics/?year=2023&limit=1")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("items") || response.get("items").isEmpty()) {
                return Collections.emptyMap();
            }

            JsonNode item = response.get("items").get(0);
            Map<String, Object> demographics = new HashMap<>();

            // Female breakdown
            Map<String, Long> female = new LinkedHashMap<>();
            female.put("0-4", parseLong(item, "f_0_4"));
            female.put("5-11", parseLong(item, "f_5_11"));
            female.put("12-17", parseLong(item, "f_12_17"));
            female.put("18-59", parseLong(item, "f_18_59"));
            female.put("60+", parseLong(item, "f_60"));
            demographics.put("female", female);

            // Male breakdown
            Map<String, Long> male = new LinkedHashMap<>();
            male.put("0-4", parseLong(item, "m_0_4"));
            male.put("5-11", parseLong(item, "m_5_11"));
            male.put("12-17", parseLong(item, "m_12_17"));
            male.put("18-59", parseLong(item, "m_18_59"));
            male.put("60+", parseLong(item, "m_60"));
            demographics.put("male", male);

            demographics.put("total", parseLong(item, "total"));
            demographics.put("femaleTotal", parseLong(item, "f_total"));
            demographics.put("maleTotal", parseLong(item, "m_total"));

            log.info("Fetched demographics for {} people", demographics.get("total"));
            return demographics;

        } catch (Exception e) {
            log.error("Error fetching UNHCR demographics: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Get solutions data (returns, resettlement, naturalization)
     */
    @Cacheable("unhcrSolutions")
    public Map<String, Long> getSolutions() {
        log.info("Fetching UNHCR solutions data...");

        try {
            JsonNode response = unhcrClient.get()
                    .uri("/solutions/?year=2023&limit=1")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("items") || response.get("items").isEmpty()) {
                return Collections.emptyMap();
            }

            JsonNode item = response.get("items").get(0);
            Map<String, Long> solutions = new LinkedHashMap<>();
            solutions.put("returnedRefugees", parseLong(item, "returned_refugees"));
            solutions.put("resettlement", parseLong(item, "resettlement"));
            solutions.put("naturalization", parseLong(item, "naturalisation"));
            solutions.put("returnedIdps", parseLong(item, "returned_idps"));

            log.info("Fetched solutions: {} returned refugees", solutions.get("returnedRefugees"));
            return solutions;

        } catch (Exception e) {
            log.error("Error fetching UNHCR solutions: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private Long parseLong(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return null;
        JsonNode value = node.get(field);
        if (value.isNumber()) return value.asLong();
        if (value.isTextual()) {
            try {
                String text = value.asText();
                if ("-".equals(text) || text.isEmpty()) return null;
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
