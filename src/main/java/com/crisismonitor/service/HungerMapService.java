package com.crisismonitor.service;

import com.crisismonitor.model.Alert;
import com.crisismonitor.model.Country;
import com.crisismonitor.model.FoodSecurityMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HungerMapService {

    private final WebClient hungerMapClient;

    @Cacheable("countries")
    public List<Country> getCountries() {
        log.info("Fetching countries from HungerMap API...");

        try {
            JsonNode response = hungerMapClient.get()
                    .uri("/v2/info/country")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("body")) {
                log.error("Invalid response from countries API");
                return Collections.emptyList();
            }

            JsonNode countries = response.get("body").get("countries");
            List<Country> result = new ArrayList<>();

            for (JsonNode node : countries) {
                JsonNode countryNode = node.get("country");
                JsonNode populationNode = node.get("population");
                JsonNode incomeNode = node.get("income_group");

                Country country = Country.builder()
                        .id(countryNode.get("id").asInt())
                        .name(countryNode.get("name").asText())
                        .iso3(countryNode.get("iso3").asText())
                        .iso2(countryNode.has("iso2") && !countryNode.get("iso2").isNull()
                                ? countryNode.get("iso2").asText() : null)
                        .population(populationNode != null && populationNode.has("number")
                                ? populationNode.get("number").asLong() : null)
                        .incomeLevel(incomeNode != null && incomeNode.has("level")
                                ? incomeNode.get("level").asText() : null)
                        .build();

                result.add(country);
            }

            log.info("Fetched {} countries", result.size());
            return result;

        } catch (Exception e) {
            log.error("Error fetching countries: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Cacheable("ipcData")
    public Map<String, Country> getIpcData() {
        log.info("Fetching IPC data from HungerMap API...");

        try {
            JsonNode response = hungerMapClient.get()
                    .uri("/v1/ipc/global")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("body")) {
                log.error("Invalid response from IPC API");
                return Collections.emptyMap();
            }

            JsonNode ipcData = response.get("body").get("ipc");
            Map<String, Country> result = new HashMap<>();

            for (JsonNode node : ipcData) {
                String iso3 = node.get("iso3").asText();

                Country country = Country.builder()
                        .iso3(iso3)
                        .name(node.get("country_name").asText())
                        .peoplePhase3to5(node.has("people_phase_3_5") ? node.get("people_phase_3_5").asLong() : null)
                        .percentPhase3to5(node.has("percent_phase_3_5") ? node.get("percent_phase_3_5").asDouble() : null)
                        .peoplePhase4to5(node.has("people_phase_4_5") ? node.get("people_phase_4_5").asLong() : null)
                        .percentPhase4to5(node.has("percent_phase_4_5") ? node.get("percent_phase_4_5").asDouble() : null)
                        .ipcAnalysisPeriod(node.has("analysis_period") ? node.get("analysis_period").asText() : null)
                        .build();

                // Compute alert level based on IPC percentages
                country.setAlertLevel(computeAlertLevel(country.getPercentPhase3to5()));

                result.put(iso3, country);
            }

            log.info("Fetched IPC data for {} countries", result.size());
            return result;

        } catch (Exception e) {
            log.error("Error fetching IPC data: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Cacheable("severityData")
    public Map<String, Integer> getSeverityData() {
        log.info("Fetching severity data from HungerMap API...");

        try {
            JsonNode response = hungerMapClient.get()
                    .uri("/v1/severity/country")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("body")) {
                log.error("Invalid response from severity API");
                return Collections.emptyMap();
            }

            JsonNode countries = response.get("body").get("countries");
            Map<String, Integer> result = new HashMap<>();

            for (JsonNode node : countries) {
                JsonNode countryNode = node.get("country");
                String iso3 = countryNode.get("iso3").asText();
                Integer tier = node.has("tier") && !node.get("tier").isNull()
                        ? node.get("tier").asInt() : null;

                if (tier != null) {
                    result.put(iso3, tier);
                }
            }

            log.info("Fetched severity data for {} countries", result.size());
            return result;

        } catch (Exception e) {
            log.error("Error fetching severity data: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Cacheable("alerts")
    public List<Alert> getAlerts() {
        log.info("Fetching alerts from HungerMap API...");

        try {
            JsonNode response = hungerMapClient.get()
                    .uri("/v1/alerts/country")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("body")) {
                log.error("Invalid response from alerts API");
                return Collections.emptyList();
            }

            JsonNode alerts = response.get("body").get("alerts");
            List<Alert> result = new ArrayList<>();

            for (JsonNode node : alerts) {
                JsonNode countryNode = node.get("country");

                Alert alert = Alert.builder()
                        .countryName(countryNode.get("name").asText())
                        .iso3(countryNode.get("iso3").asText())
                        .type(node.get("type").asText())
                        .category(node.get("category").asText())
                        .value(node.has("value") ? node.get("value").asDouble() : null)
                        .fcsChange(node.has("fcs_change") ? node.get("fcs_change").asDouble() : null)
                        .fcsPrevalence(node.has("fcs_prevalence") ? node.get("fcs_prevalence").asDouble() : null)
                        .build();

                // Compute severity based on alert type
                alert.setSeverity(computeAlertSeverity(alert));

                result.add(alert);
            }

            log.info("Fetched {} alerts", result.size());
            return result;

        } catch (Exception e) {
            log.error("Error fetching alerts: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String computeAlertLevel(Double percentPhase3to5) {
        if (percentPhase3to5 == null) return "NO_DATA";
        if (percentPhase3to5 >= 50) return "CRITICAL";
        if (percentPhase3to5 >= 30) return "HIGH";
        if (percentPhase3to5 >= 15) return "MEDIUM";
        return "LOW";
    }

    private String computeAlertSeverity(Alert alert) {
        String type = alert.getType();
        if (type.contains("FATALITIES") || type.contains("FCS_DETERIORATION")) {
            return "CRITICAL";
        }
        if (type.contains("HIGH")) {
            return "HIGH";
        }
        return "MEDIUM";
    }

    @Cacheable("foodSecurityMetrics")
    public List<FoodSecurityMetrics> getFoodSecurityMetrics() {
        log.info("Fetching food security metrics from HungerMap API...");

        try {
            JsonNode response = hungerMapClient.get()
                    .uri("/v1/foodsecurity/country?days_ago=0")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("body")) {
                log.error("Invalid response from food security API");
                return Collections.emptyList();
            }

            JsonNode countries = response.get("body").get("countries");
            List<FoodSecurityMetrics> result = new ArrayList<>();

            for (JsonNode node : countries) {
                JsonNode countryNode = node.get("country");
                JsonNode metricsNode = node.get("metrics");

                FoodSecurityMetrics.FoodSecurityMetricsBuilder builder = FoodSecurityMetrics.builder()
                        .iso3(countryNode.get("iso3").asText())
                        .name(countryNode.get("name").asText())
                        .dataType(node.has("dataType") ? node.get("dataType").asText() : null)
                        .date(node.has("date") ? LocalDate.parse(node.get("date").asText()) : null);

                // FCS metrics
                if (metricsNode.has("fcs")) {
                    JsonNode fcs = metricsNode.get("fcs");
                    builder.fcsPeople(fcs.has("people") ? fcs.get("people").asLong() : null)
                           .fcsPrevalence(fcs.has("prevalence") ? fcs.get("prevalence").asDouble() : null);
                }

                // rCSI metrics
                if (metricsNode.has("rcsi")) {
                    JsonNode rcsi = metricsNode.get("rcsi");
                    builder.rcsiPeople(rcsi.has("people") ? rcsi.get("people").asLong() : null)
                           .rcsiPrevalence(rcsi.has("prevalence") ? rcsi.get("prevalence").asDouble() : null);
                }

                // Health Access
                if (metricsNode.has("healthAccess")) {
                    JsonNode ha = metricsNode.get("healthAccess");
                    builder.healthAccessPeople(ha.has("people") ? ha.get("people").asLong() : null)
                           .healthAccessPrevalence(ha.has("prevalence") ? ha.get("prevalence").asDouble() : null);
                }

                // Market Access
                if (metricsNode.has("marketAccess")) {
                    JsonNode ma = metricsNode.get("marketAccess");
                    builder.marketAccessPeople(ma.has("people") ? ma.get("people").asLong() : null)
                           .marketAccessPrevalence(ma.has("prevalence") ? ma.get("prevalence").asDouble() : null);
                }

                // Livelihood Coping
                if (metricsNode.has("livelihoodCoping")) {
                    JsonNode lc = metricsNode.get("livelihoodCoping");
                    builder.livelihoodCopingPeople(lc.has("people") ? lc.get("people").asLong() : null)
                           .livelihoodCopingPrevalence(lc.has("prevalence") ? lc.get("prevalence").asDouble() : null);
                }

                result.add(builder.build());
            }

            log.info("Fetched food security metrics for {} countries", result.size());
            return result;

        } catch (Exception e) {
            log.error("Error fetching food security metrics: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Fetch food security metrics for N days ago (for bootstrapping nowcast history).
     */
    public List<FoodSecurityMetrics> getFoodSecurityMetricsForDaysAgo(int daysAgo) {
        try {
            JsonNode response = hungerMapClient.get()
                    .uri("/v1/foodsecurity/country?days_ago=" + daysAgo)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("body")) {
                return Collections.emptyList();
            }

            JsonNode countries = response.get("body").get("countries");
            List<FoodSecurityMetrics> result = new ArrayList<>();

            for (JsonNode node : countries) {
                JsonNode countryNode = node.get("country");
                JsonNode metricsNode = node.get("metrics");

                FoodSecurityMetrics.FoodSecurityMetricsBuilder builder = FoodSecurityMetrics.builder()
                        .iso3(countryNode.get("iso3").asText())
                        .name(countryNode.get("name").asText());

                if (metricsNode.has("fcs")) {
                    JsonNode fcs = metricsNode.get("fcs");
                    if (fcs.has("prevalence")) builder.fcsPrevalence(fcs.get("prevalence").asDouble());
                    if (fcs.has("people")) builder.fcsPeople(fcs.get("people").asLong());
                }
                if (metricsNode.has("rcsi")) {
                    JsonNode rcsi = metricsNode.get("rcsi");
                    if (rcsi.has("prevalence")) builder.rcsiPrevalence(rcsi.get("prevalence").asDouble());
                    if (rcsi.has("people")) builder.rcsiPeople(rcsi.get("people").asLong());
                }

                result.add(builder.build());
            }
            return result;
        } catch (Exception e) {
            log.debug("Error fetching food security for days_ago={}: {}", daysAgo, e.getMessage());
            return Collections.emptyList();
        }
    }
}
