package com.crisismonitor.service;

import com.crisismonitor.model.CountryProfile;
import com.crisismonitor.model.EconomicIndicator;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WorldBankService {

    private final WebClient worldBankClient;

    // Key indicators for crisis monitoring
    private static final String INFLATION = "FP.CPI.TOTL.ZG";      // Consumer price inflation %
    private static final String GDP_PER_CAPITA = "NY.GDP.PCAP.CD"; // GDP per capita USD
    private static final String POPULATION = "SP.POP.TOTL";         // Total population
    private static final String POVERTY = "SI.POV.DDAY";            // Poverty headcount $2.15/day

    public WorldBankService() {
        this.worldBankClient = WebClient.builder()
                .baseUrl("https://api.worldbank.org/v2")
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @Cacheable("countryProfiles")
    public List<CountryProfile> getCountryProfiles() {
        log.info("Fetching country profiles from World Bank API...");

        try {
            JsonNode response = worldBankClient.get()
                    .uri("/country/all?format=json&per_page=300")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.isArray() || response.size() < 2) {
                log.error("Invalid response from World Bank countries API");
                return Collections.emptyList();
            }

            JsonNode countries = response.get(1);
            List<CountryProfile> result = new ArrayList<>();

            for (JsonNode node : countries) {
                // Skip aggregates (regions, income groups)
                String incomeLevel = node.has("incomeLevel") && node.get("incomeLevel").has("id")
                        ? node.get("incomeLevel").get("id").asText() : "";
                if ("NA".equals(incomeLevel) || incomeLevel.isEmpty()) {
                    continue;
                }

                String iso3 = node.get("id").asText();
                Double lat = null, lon = null;
                if (node.has("latitude") && !node.get("latitude").asText().isEmpty()) {
                    try {
                        lat = Double.parseDouble(node.get("latitude").asText());
                        lon = Double.parseDouble(node.get("longitude").asText());
                    } catch (NumberFormatException e) {
                        // Skip invalid coordinates
                    }
                }

                CountryProfile profile = CountryProfile.builder()
                        .iso3(iso3)
                        .iso2(node.get("iso2Code").asText())
                        .name(node.get("name").asText())
                        .region(node.has("region") && node.get("region").has("value")
                                ? node.get("region").get("value").asText().trim() : null)
                        .incomeLevel(node.has("incomeLevel") && node.get("incomeLevel").has("value")
                                ? node.get("incomeLevel").get("value").asText() : null)
                        .capitalCity(node.has("capitalCity") && !node.get("capitalCity").asText().isEmpty()
                                ? node.get("capitalCity").asText() : null)
                        .latitude(lat)
                        .longitude(lon)
                        .build();

                result.add(profile);
            }

            log.info("Fetched {} country profiles", result.size());
            return result;

        } catch (Exception e) {
            log.error("Error fetching country profiles: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Cacheable("inflationData")
    public List<EconomicIndicator> getInflationData() {
        return fetchIndicator(INFLATION, "Inflation");
    }

    @Cacheable("gdpData")
    public List<EconomicIndicator> getGdpPerCapitaData() {
        return fetchIndicator(GDP_PER_CAPITA, "GDP per capita");
    }

    @Cacheable("populationData")
    public List<EconomicIndicator> getPopulationData() {
        return fetchIndicator(POPULATION, "Population");
    }

    @Cacheable("povertyData")
    public List<EconomicIndicator> getPovertyData() {
        return fetchIndicator(POVERTY, "Poverty rate");
    }

    private List<EconomicIndicator> fetchIndicator(String indicatorCode, String indicatorLabel) {
        log.info("Fetching {} data from World Bank API...", indicatorLabel);

        try {
            // Fetch last 5 years of data for all countries
            JsonNode response = worldBankClient.get()
                    .uri("/country/all/indicator/{indicator}?format=json&per_page=1500&date=2019:2024",
                            indicatorCode)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.isArray() || response.size() < 2) {
                log.error("Invalid response from World Bank {} API", indicatorLabel);
                return Collections.emptyList();
            }

            JsonNode data = response.get(1);
            List<EconomicIndicator> result = new ArrayList<>();

            for (JsonNode node : data) {
                if (node.get("value").isNull()) continue;

                EconomicIndicator indicator = EconomicIndicator.builder()
                        .iso3(node.get("countryiso3code").asText())
                        .countryName(node.has("country") && node.get("country").has("value")
                                ? node.get("country").get("value").asText() : null)
                        .indicatorCode(indicatorCode)
                        .indicatorName(node.has("indicator") && node.get("indicator").has("value")
                                ? node.get("indicator").get("value").asText() : indicatorLabel)
                        .year(Integer.parseInt(node.get("date").asText()))
                        .value(node.get("value").asDouble())
                        .build();

                result.add(indicator);
            }

            log.info("Fetched {} {} records", result.size(), indicatorLabel);
            return result;

        } catch (Exception e) {
            log.error("Error fetching {} data: {}", indicatorLabel, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get the latest value for each country for a given indicator
     */
    public Map<String, EconomicIndicator> getLatestByCountry(List<EconomicIndicator> indicators) {
        return indicators.stream()
                .collect(Collectors.groupingBy(
                        EconomicIndicator::getIso3,
                        Collectors.collectingAndThen(
                                Collectors.maxBy(Comparator.comparing(EconomicIndicator::getYear)),
                                opt -> opt.orElse(null)
                        )
                ))
                .entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Get countries with high inflation (>15%)
     */
    public List<EconomicIndicator> getHighInflationCountries() {
        Map<String, EconomicIndicator> latest = getLatestByCountry(getInflationData());
        return latest.values().stream()
                .filter(i -> i.getValue() != null && i.getValue() >= 15)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .toList();
    }

    /**
     * Get enriched country profiles with economic data
     */
    public List<CountryProfile> getEnrichedProfiles() {
        List<CountryProfile> profiles = getCountryProfiles();
        Map<String, EconomicIndicator> inflationMap = getLatestByCountry(getInflationData());
        Map<String, EconomicIndicator> gdpMap = getLatestByCountry(getGdpPerCapitaData());
        Map<String, EconomicIndicator> popMap = getLatestByCountry(getPopulationData());
        Map<String, EconomicIndicator> povertyMap = getLatestByCountry(getPovertyData());

        for (CountryProfile profile : profiles) {
            String iso3 = profile.getIso3();

            // Inflation
            if (inflationMap.containsKey(iso3)) {
                EconomicIndicator inf = inflationMap.get(iso3);
                profile.setInflation(inf.getValue());
                profile.setInflationYear(inf.getYear());
                profile.setInflationAlert(EconomicIndicator.computeInflationAlert(inf.getValue()));
            }

            // GDP
            if (gdpMap.containsKey(iso3)) {
                EconomicIndicator gdp = gdpMap.get(iso3);
                profile.setGdpPerCapita(gdp.getValue());
                profile.setGdpYear(gdp.getYear());
            }

            // Population
            if (popMap.containsKey(iso3)) {
                EconomicIndicator pop = popMap.get(iso3);
                profile.setPopulation(pop.getValue().longValue());
                profile.setPopulationYear(pop.getYear());
            }

            // Poverty
            if (povertyMap.containsKey(iso3)) {
                EconomicIndicator pov = povertyMap.get(iso3);
                profile.setPovertyRate(pov.getValue());
                profile.setPovertyYear(pov.getYear());
            }
        }

        return profiles;
    }
}
