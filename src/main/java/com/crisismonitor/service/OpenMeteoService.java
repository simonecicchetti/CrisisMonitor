package com.crisismonitor.service;

import com.crisismonitor.model.PrecipitationAnomaly;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Open-Meteo Service - Free climate data API (no key required)
 *
 * Calculates precipitation anomalies by comparing recent rainfall
 * against historical averages for the same period.
 *
 * API: https://open-meteo.com/en/docs/historical-weather-api
 */
@Slf4j
@Service
public class OpenMeteoService {

    private final WebClient openMeteoClient;
    private final WebClient archiveClient;

    private static final String FORECAST_API = "https://api.open-meteo.com/v1/forecast";
    private static final String ARCHIVE_API = "https://archive-api.open-meteo.com/v1/archive";

    // Country coordinates (capital or main agricultural region)
    // ISO2 -> [latitude, longitude, locationName]
    // Tier 1: 33 high-risk countries for climate monitoring
    private static final Map<String, Object[]> COUNTRY_COORDS = Map.ofEntries(
            // Africa - Sahel & Horn
            Map.entry("SD", new Object[]{15.5, 32.5, "Khartoum"}),
            Map.entry("SS", new Object[]{4.85, 31.58, "Juba"}),
            Map.entry("SO", new Object[]{2.04, 45.34, "Mogadishu"}),
            Map.entry("ET", new Object[]{9.0, 38.75, "Addis Ababa"}),
            Map.entry("CD", new Object[]{-4.32, 15.31, "Kinshasa"}),
            Map.entry("NG", new Object[]{9.06, 7.49, "Abuja"}),
            Map.entry("ML", new Object[]{12.65, -8.0, "Bamako"}),
            Map.entry("BF", new Object[]{12.37, -1.52, "Ouagadougou"}),
            Map.entry("NE", new Object[]{13.51, 2.11, "Niamey"}),
            Map.entry("TD", new Object[]{12.11, 15.04, "N'Djamena"}),
            Map.entry("CF", new Object[]{4.36, 18.56, "Bangui"}),
            Map.entry("MZ", new Object[]{-25.97, 32.58, "Maputo"}),
            Map.entry("KE", new Object[]{-1.29, 36.82, "Nairobi"}),
            Map.entry("UG", new Object[]{0.35, 32.58, "Kampala"}),
            Map.entry("ZW", new Object[]{-17.83, 31.05, "Harare"}),
            // Middle East
            Map.entry("YE", new Object[]{15.35, 44.2, "Sanaa"}),
            Map.entry("AF", new Object[]{34.53, 69.17, "Kabul"}),
            Map.entry("IQ", new Object[]{33.31, 44.37, "Baghdad"}),
            Map.entry("SY", new Object[]{33.51, 36.29, "Damascus"}),
            Map.entry("LB", new Object[]{33.89, 35.50, "Beirut"}),
            // South Asia
            Map.entry("PK", new Object[]{33.69, 73.06, "Islamabad"}),
            Map.entry("BD", new Object[]{23.81, 90.41, "Dhaka"}),
            Map.entry("IN", new Object[]{28.61, 77.21, "New Delhi"}),
            Map.entry("MM", new Object[]{19.75, 96.1, "Naypyidaw"}),
            // Southeast Asia & Pacific
            Map.entry("PH", new Object[]{14.60, 120.98, "Manila"}),
            Map.entry("ID", new Object[]{-6.21, 106.85, "Jakarta"}),
            Map.entry("VN", new Object[]{21.03, 105.85, "Hanoi"}),
            // Latin America & Caribbean
            Map.entry("HT", new Object[]{18.54, -72.34, "Port-au-Prince"}),
            Map.entry("PE", new Object[]{-12.05, -77.04, "Lima"}),
            Map.entry("CO", new Object[]{4.71, -74.07, "Bogota"}),
            Map.entry("EC", new Object[]{-0.18, -78.47, "Quito"}),
            Map.entry("GT", new Object[]{14.63, -90.51, "Guatemala City"}),
            Map.entry("HN", new Object[]{14.07, -87.19, "Tegucigalpa"})
    );

    // ISO2 to ISO3 mapping
    private static final Map<String, String> ISO2_TO_ISO3 = Map.ofEntries(
            // Africa
            Map.entry("SD", "SDN"), Map.entry("SS", "SSD"), Map.entry("SO", "SOM"),
            Map.entry("ET", "ETH"), Map.entry("CD", "COD"), Map.entry("NG", "NGA"),
            Map.entry("ML", "MLI"), Map.entry("BF", "BFA"), Map.entry("NE", "NER"),
            Map.entry("TD", "TCD"), Map.entry("CF", "CAF"), Map.entry("MZ", "MOZ"),
            Map.entry("KE", "KEN"), Map.entry("UG", "UGA"), Map.entry("ZW", "ZWE"),
            // Middle East
            Map.entry("YE", "YEM"), Map.entry("AF", "AFG"), Map.entry("IQ", "IRQ"),
            Map.entry("SY", "SYR"), Map.entry("LB", "LBN"),
            // South Asia
            Map.entry("PK", "PAK"), Map.entry("BD", "BGD"), Map.entry("IN", "IND"),
            Map.entry("MM", "MMR"),
            // Southeast Asia
            Map.entry("PH", "PHL"), Map.entry("ID", "IDN"), Map.entry("VN", "VNM"),
            // Latin America
            Map.entry("HT", "HTI"), Map.entry("PE", "PER"), Map.entry("CO", "COL"),
            Map.entry("EC", "ECU"), Map.entry("GT", "GTM"), Map.entry("HN", "HND")
    );

    private static final Map<String, String> COUNTRY_NAMES = Map.ofEntries(
            // Africa
            Map.entry("SD", "Sudan"), Map.entry("SS", "South Sudan"), Map.entry("SO", "Somalia"),
            Map.entry("ET", "Ethiopia"), Map.entry("CD", "DR Congo"), Map.entry("NG", "Nigeria"),
            Map.entry("ML", "Mali"), Map.entry("BF", "Burkina Faso"), Map.entry("NE", "Niger"),
            Map.entry("TD", "Chad"), Map.entry("CF", "CAR"), Map.entry("MZ", "Mozambique"),
            Map.entry("KE", "Kenya"), Map.entry("UG", "Uganda"), Map.entry("ZW", "Zimbabwe"),
            // Middle East
            Map.entry("YE", "Yemen"), Map.entry("AF", "Afghanistan"), Map.entry("IQ", "Iraq"),
            Map.entry("SY", "Syria"), Map.entry("LB", "Lebanon"),
            // South Asia
            Map.entry("PK", "Pakistan"), Map.entry("BD", "Bangladesh"), Map.entry("IN", "India"),
            Map.entry("MM", "Myanmar"),
            // Southeast Asia
            Map.entry("PH", "Philippines"), Map.entry("ID", "Indonesia"), Map.entry("VN", "Vietnam"),
            // Latin America
            Map.entry("HT", "Haiti"), Map.entry("PE", "Peru"), Map.entry("CO", "Colombia"),
            Map.entry("EC", "Ecuador"), Map.entry("GT", "Guatemala"), Map.entry("HN", "Honduras")
    );

    public OpenMeteoService() {
        this.openMeteoClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
        this.archiveClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
    }

    /**
     * Get precipitation anomaly for a single country
     */
    @Cacheable(value = "precipAnomaly", key = "#iso2", unless = "#result == null")
    public PrecipitationAnomaly getPrecipitationAnomaly(String iso2) {
        Object[] coords = COUNTRY_COORDS.get(iso2);
        if (coords == null) {
            log.warn("No coordinates for country: {}", iso2);
            return null;
        }

        double lat = (Double) coords[0];
        double lon = (Double) coords[1];
        String locationName = (String) coords[2];

        log.info("Fetching precipitation anomaly for {} ({}, {})", iso2, lat, lon);

        try {
            // Get last 30 days precipitation
            LocalDate today = LocalDate.now();
            LocalDate thirtyDaysAgo = today.minusDays(30);

            double currentPrecip = getRecentPrecipitation(lat, lon, 30);

            // Get historical average for same period (using last 5 years average)
            double historicalAvg = getHistoricalAverage(lat, lon, thirtyDaysAgo, today);

            // Calculate anomaly
            double anomalyMm = currentPrecip - historicalAvg;
            double anomalyPercent = historicalAvg > 0
                    ? ((currentPrecip - historicalAvg) / historicalAvg) * 100
                    : 0;

            String category = PrecipitationAnomaly.classifyAnomaly(anomalyPercent);
            int riskScore = PrecipitationAnomaly.calculateRiskScore(anomalyPercent);

            return PrecipitationAnomaly.builder()
                    .iso2(iso2)
                    .iso3(ISO2_TO_ISO3.get(iso2))
                    .countryName(COUNTRY_NAMES.get(iso2))
                    .latitude(lat)
                    .longitude(lon)
                    .locationName(locationName)
                    .currentPrecipMm(Math.round(currentPrecip * 10) / 10.0)
                    .normalPrecipMm(Math.round(historicalAvg * 10) / 10.0)
                    .anomalyMm(Math.round(anomalyMm * 10) / 10.0)
                    .anomalyPercent(Math.round(anomalyPercent * 10) / 10.0)
                    .spiCategory(category)
                    .riskLevel(category.contains("DROUGHT") ? "DROUGHT" :
                              category.contains("DRY") ? "DRY" :
                              category.contains("WET") ? "WET" : "NORMAL")
                    .riskScore(riskScore)
                    .drought(anomalyPercent < -20)
                    .severeDrought(anomalyPercent < -40)
                    .periodStart(thirtyDaysAgo)
                    .periodEnd(today)
                    .calculatedAt(LocalDate.now())
                    .build();

        } catch (Exception e) {
            log.error("Error calculating precipitation anomaly for {}: {}", iso2, e.getMessage());
            return null;
        }
    }

    /**
     * Get recent precipitation (last N days) from forecast API
     */
    private double getRecentPrecipitation(double lat, double lon, int days) {
        try {
            String url = String.format(
                    "%s?latitude=%.2f&longitude=%.2f&daily=precipitation_sum&past_days=%d&forecast_days=0&timezone=auto",
                    FORECAST_API, lat, lon, days
            );

            JsonNode response = openMeteoClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null && response.has("daily")) {
                JsonNode precipArray = response.get("daily").get("precipitation_sum");
                double total = 0;
                for (JsonNode val : precipArray) {
                    if (!val.isNull()) {
                        total += val.asDouble();
                    }
                }
                return total;
            }
        } catch (Exception e) {
            log.error("Error fetching recent precipitation: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * Get historical average for the same period (average of last 5 years)
     */
    private double getHistoricalAverage(double lat, double lon, LocalDate periodStart, LocalDate periodEnd) {
        double totalPrecip = 0;
        int validYears = 0;

        // Get data for same period in previous years (2020-2024)
        for (int yearsBack = 1; yearsBack <= 5; yearsBack++) {
            try {
                LocalDate histStart = periodStart.minusYears(yearsBack);
                LocalDate histEnd = periodEnd.minusYears(yearsBack);

                String url = String.format(
                        "%s?latitude=%.2f&longitude=%.2f&start_date=%s&end_date=%s&daily=precipitation_sum&timezone=auto",
                        ARCHIVE_API, lat, lon,
                        histStart.format(DateTimeFormatter.ISO_DATE),
                        histEnd.format(DateTimeFormatter.ISO_DATE)
                );

                JsonNode response = archiveClient.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();

                if (response != null && response.has("daily")) {
                    JsonNode precipArray = response.get("daily").get("precipitation_sum");
                    double yearTotal = 0;
                    for (JsonNode val : precipArray) {
                        if (!val.isNull()) {
                            yearTotal += val.asDouble();
                        }
                    }
                    totalPrecip += yearTotal;
                    validYears++;
                }

                // Small delay to avoid rate limiting
                Thread.sleep(100);

            } catch (Exception e) {
                log.warn("Could not get historical data for year -{}: {}", yearsBack, e.getMessage());
            }
        }

        return validYears > 0 ? totalPrecip / validYears : 0;
    }

    /**
     * Get precipitation anomalies for all monitored countries
     */
    @Cacheable("allPrecipAnomalies")
    public List<PrecipitationAnomaly> getAllPrecipitationAnomalies() {
        log.info("Fetching precipitation anomalies for all monitored countries");

        List<PrecipitationAnomaly> anomalies = new ArrayList<>();

        for (String iso2 : COUNTRY_COORDS.keySet()) {
            try {
                PrecipitationAnomaly anomaly = getPrecipitationAnomaly(iso2);
                if (anomaly != null) {
                    anomalies.add(anomaly);
                }
                // Delay between requests
                Thread.sleep(200);
            } catch (Exception e) {
                log.warn("Error getting precipitation for {}: {}", iso2, e.getMessage());
            }
        }

        // Sort by risk score descending
        anomalies.sort((a, b) -> Integer.compare(b.getRiskScore(), a.getRiskScore()));

        log.info("Got precipitation anomalies for {} countries", anomalies.size());
        return anomalies;
    }

    /**
     * Get countries currently experiencing drought
     */
    public List<PrecipitationAnomaly> getDroughtCountries() {
        return getAllPrecipitationAnomalies().stream()
                .filter(PrecipitationAnomaly::isDrought)
                .toList();
    }

    public Set<String> getMonitoredCountries() {
        return COUNTRY_COORDS.keySet();
    }
}
