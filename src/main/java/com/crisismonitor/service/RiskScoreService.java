package com.crisismonitor.service;

import com.crisismonitor.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Risk Score Service - Predictive crisis scoring engine
 *
 * Combines multiple leading indicators:
 * 1. Climate (precipitation anomaly from Open-Meteo)
 * 2. Conflict (GDELT media spike z-score)
 * 3. Economic (currency devaluation)
 * 4. Food Security (IPC phase from FEWS NET)
 *
 * Uses 2-of-3 confirmation rule to reduce false positives:
 * Warning triggers only if ≥2 indicators are elevated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskScoreService {

    private final OpenMeteoService openMeteoService;
    private final CurrencyService currencyService;
    private final GDELTService gdeltService;
    private final FewsNetService fewsNetService;
    private final TrendTrackingService trendTrackingService;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private CacheWarmupService cacheWarmupService;

    // ISO2 to ISO3 mapping - all monitored countries
    private static final Map<String, String> ISO2_TO_ISO3 = Map.ofEntries(
            // Africa - East
            Map.entry("SD", "SDN"), Map.entry("SS", "SSD"), Map.entry("SO", "SOM"),
            Map.entry("ET", "ETH"), Map.entry("KE", "KEN"), Map.entry("UG", "UGA"),
            // Africa - Central
            Map.entry("CD", "COD"), Map.entry("CF", "CAF"), Map.entry("TD", "TCD"),
            Map.entry("CM", "CMR"), Map.entry("RW", "RWA"), Map.entry("BI", "BDI"),
            // Africa - West
            Map.entry("NG", "NGA"), Map.entry("ML", "MLI"), Map.entry("BF", "BFA"),
            Map.entry("NE", "NER"),
            // Africa - Other
            Map.entry("LY", "LBY"), Map.entry("MZ", "MOZ"), Map.entry("ZW", "ZWE"),
            // MENA
            Map.entry("YE", "YEM"), Map.entry("AF", "AFG"), Map.entry("IQ", "IRQ"),
            Map.entry("SY", "SYR"), Map.entry("LB", "LBN"), Map.entry("PS", "PSE"),
            Map.entry("IR", "IRN"),
            // Asia
            Map.entry("PK", "PAK"), Map.entry("BD", "BGD"), Map.entry("IN", "IND"),
            Map.entry("MM", "MMR"),
            // Southeast Asia
            Map.entry("PH", "PHL"), Map.entry("ID", "IDN"), Map.entry("VN", "VNM"),
            // LAC
            Map.entry("HT", "HTI"), Map.entry("VE", "VEN"), Map.entry("CO", "COL"),
            Map.entry("GT", "GTM"), Map.entry("HN", "HND"), Map.entry("SV", "SLV"),
            Map.entry("NI", "NIC"), Map.entry("MX", "MEX"), Map.entry("PE", "PER"),
            Map.entry("EC", "ECU"), Map.entry("CU", "CUB"), Map.entry("PA", "PAN"),
            // Europe
            Map.entry("UA", "UKR")
    );

    private static final Map<String, String> COUNTRY_NAMES = Map.ofEntries(
            // Africa - East
            Map.entry("SD", "Sudan"), Map.entry("SS", "South Sudan"), Map.entry("SO", "Somalia"),
            Map.entry("ET", "Ethiopia"), Map.entry("KE", "Kenya"), Map.entry("UG", "Uganda"),
            // Africa - Central
            Map.entry("CD", "DR Congo"), Map.entry("CF", "CAR"), Map.entry("TD", "Chad"),
            Map.entry("CM", "Cameroon"), Map.entry("RW", "Rwanda"), Map.entry("BI", "Burundi"),
            // Africa - West
            Map.entry("NG", "Nigeria"), Map.entry("ML", "Mali"), Map.entry("BF", "Burkina Faso"),
            Map.entry("NE", "Niger"),
            // Africa - Other
            Map.entry("LY", "Libya"), Map.entry("MZ", "Mozambique"), Map.entry("ZW", "Zimbabwe"),
            // MENA
            Map.entry("YE", "Yemen"), Map.entry("AF", "Afghanistan"), Map.entry("IQ", "Iraq"),
            Map.entry("SY", "Syria"), Map.entry("LB", "Lebanon"), Map.entry("PS", "Palestine"),
            Map.entry("IR", "Iran"),
            // Asia
            Map.entry("PK", "Pakistan"), Map.entry("BD", "Bangladesh"), Map.entry("IN", "India"),
            Map.entry("MM", "Myanmar"),
            // Southeast Asia
            Map.entry("PH", "Philippines"), Map.entry("ID", "Indonesia"), Map.entry("VN", "Vietnam"),
            // LAC
            Map.entry("HT", "Haiti"), Map.entry("VE", "Venezuela"), Map.entry("CO", "Colombia"),
            Map.entry("GT", "Guatemala"), Map.entry("HN", "Honduras"), Map.entry("SV", "El Salvador"),
            Map.entry("NI", "Nicaragua"), Map.entry("MX", "Mexico"), Map.entry("PE", "Peru"),
            Map.entry("EC", "Ecuador"), Map.entry("CU", "Cuba"), Map.entry("PA", "Panama"),
            // Europe
            Map.entry("UA", "Ukraine")
    );

    // Thresholds for "elevated" status
    private static final double CLIMATE_THRESHOLD = 30;    // Risk score >= 30
    private static final double CONFLICT_THRESHOLD = 1.0;  // z-score >= 1.0
    private static final double ECONOMIC_THRESHOLD = 30;   // Risk score >= 30 (or >5% devaluation)
    private static final double IPC_THRESHOLD = 3.0;       // IPC Phase >= 3 (Crisis)

    /**
     * Calculate risk score for a single country
     */
    @Cacheable(value = "riskScore", key = "#iso2", sync = true)
    public RiskScore calculateRiskScore(String iso2) {
        log.info("Calculating risk score for {}", iso2);

        String iso3 = ISO2_TO_ISO3.get(iso2);
        String countryName = COUNTRY_NAMES.get(iso2);

        if (iso3 == null) {
            log.warn("Unknown country: {}", iso2);
            return null;
        }

        // Gather data from all sources
        int climateScore = 0;
        int conflictScore = 0;
        int economicScore = 0;
        int foodSecurityScore = 0;

        Double precipAnomaly = null;
        Double gdeltZScore = null;
        Double currencyChange = null;
        Double ipcPhase = null;

        double confidence = 0;
        int dataPoints = 0;

        // 1. Climate data (Open-Meteo)
        try {
            PrecipitationAnomaly precip = openMeteoService.getPrecipitationAnomaly(iso2);
            if (precip != null) {
                climateScore = precip.getRiskScore();
                precipAnomaly = precip.getAnomalyPercent();
                dataPoints++;
            }
        } catch (Exception e) {
            log.warn("Could not get climate data for {}: {}", iso2, e.getMessage());
        }

        // 2. Conflict data (GDELT) - only use if batch cache is ready (avoids individual API calls)
        try {
            MediaSpike spike = null;
            if (cacheWarmupService != null && cacheWarmupService.isCacheReady("conflict")) {
                spike = gdeltService.getConflictSpikeIndex(iso3);
            }
            if (spike != null && spike.getZScore() != null) {
                gdeltZScore = spike.getZScore();
                // Convert z-score to 0-100 score
                conflictScore = Math.min(100, Math.max(0, (int)(gdeltZScore * 25 + 25)));

                // Volume floor for chronic conflicts: high absolute article count
                // indicates ongoing conflict even if z-score is low (no spike vs already-high baseline)
                if (spike.getArticlesLast7Days() != null) {
                    int articles = spike.getArticlesLast7Days();
                    int volumeFloor = 0;
                    if (articles > 200) volumeFloor = 75;
                    else if (articles > 100) volumeFloor = 60;
                    else if (articles > 50) volumeFloor = 45;
                    conflictScore = Math.max(conflictScore, volumeFloor);
                }

                dataPoints++;
            }
        } catch (Exception e) {
            log.warn("Could not get conflict data for {}: {}", iso3, e.getMessage());
        }

        // 3. Economic data (Currency)
        try {
            CurrencyData currency = currencyService.getCurrencyData(iso2);
            if (currency != null) {
                economicScore = currency.getRiskScore();
                currencyChange = currency.getChange30d();
                dataPoints++;
            }
        } catch (Exception e) {
            log.warn("Could not get currency data for {}: {}", iso2, e.getMessage());
        }

        // 4. Food Security data (IPC)
        try {
            IPCAlert ipc = fewsNetService.getLatestIPCPhase(iso2);
            if (ipc != null && ipc.getIpcPhase() != null) {
                ipcPhase = ipc.getIpcPhase();
                // Convert IPC phase to 0-100 score
                foodSecurityScore = (int)(ipcPhase * 20);  // Phase 5 = 100
                dataPoints++;
            }
        } catch (Exception e) {
            log.warn("Could not get IPC data for {}: {}", iso2, e.getMessage());
        }

        // Calculate confidence based on data availability
        confidence = dataPoints / 4.0;

        // Determine which indicators are elevated
        boolean climateElevated = climateScore >= CLIMATE_THRESHOLD;
        boolean conflictElevated = gdeltZScore != null && gdeltZScore >= CONFLICT_THRESHOLD;
        boolean economicElevated = economicScore >= ECONOMIC_THRESHOLD;
        int elevatedCount = (climateElevated ? 1 : 0) + (conflictElevated ? 1 : 0) + (economicElevated ? 1 : 0);

        // Calculate weighted overall score
        // Weights: Climate 25%, Conflict 25%, Economic 20%, Food Security 30%
        int overallScore = (int)(
                climateScore * 0.25 +
                conflictScore * 0.25 +
                economicScore * 0.20 +
                foodSecurityScore * 0.30
        );

        // Apply 2-of-3 confirmation rule
        // If only 1 indicator elevated, reduce score by 30%
        if (elevatedCount < 2 && overallScore > 30) {
            overallScore = (int)(overallScore * 0.7);
        }

        // Determine risk level
        String riskLevel;
        if (overallScore >= 86) riskLevel = "CRITICAL";
        else if (overallScore >= 71) riskLevel = "ALERT";
        else if (overallScore >= 51) riskLevel = "WARNING";
        else if (overallScore >= 31) riskLevel = "WATCH";
        else riskLevel = "STABLE";

        // Determine primary drivers
        List<String> drivers = new ArrayList<>();
        Map<String, Integer> driverScores = new LinkedHashMap<>();
        driverScores.put("Food Security", foodSecurityScore);
        driverScores.put("Climate", climateScore);
        driverScores.put("Conflict", conflictScore);
        driverScores.put("Economic", economicScore);

        // Sort by score and take top contributors
        drivers = driverScores.entrySet().stream()
                .filter(e -> e.getValue() >= 30)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .limit(3)
                .collect(Collectors.toList());

        if (drivers.isEmpty()) {
            drivers.add("No significant drivers");
        }

        // Determine time horizon
        String horizon;
        String horizonReason;
        if (conflictElevated && climateElevated) {
            horizon = "30 days";
            horizonReason = "Conflict + climate stress accelerates crisis";
        } else if (conflictElevated) {
            horizon = "30-60 days";
            horizonReason = "Conflict-driven crises develop rapidly";
        } else if (climateElevated) {
            horizon = "60-90 days";
            horizonReason = "Climate impacts develop over agricultural cycle";
        } else if (economicElevated) {
            horizon = "60-90 days";
            horizonReason = "Economic shocks take time to impact food security";
        } else {
            horizon = "90+ days";
            horizonReason = "No immediate crisis drivers detected";
        }

        // Confidence note
        String confidenceNote;
        if (confidence >= 0.75) confidenceNote = "High - all data sources available";
        else if (confidence >= 0.5) confidenceNote = "Medium - some data sources unavailable";
        else confidenceNote = "Low - limited data availability";

        return RiskScore.builder()
                .iso3(iso3)
                .iso2(iso2)
                .countryName(countryName)
                .score(overallScore)
                .riskLevel(riskLevel)
                .climateScore(climateScore)
                .conflictScore(conflictScore)
                .economicScore(economicScore)
                .foodSecurityScore(foodSecurityScore)
                .climateElevated(climateElevated)
                .conflictElevated(conflictElevated)
                .economicElevated(economicElevated)
                .elevatedCount(elevatedCount)
                .drivers(drivers)
                .horizon(horizon)
                .horizonReason(horizonReason)
                .confidence(Math.round(confidence * 100) / 100.0)
                .confidenceNote(confidenceNote)
                .precipitationAnomaly(precipAnomaly)
                .currencyChange30d(currencyChange)
                .gdeltZScore(gdeltZScore)
                .ipcPhase(ipcPhase)
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Get risk scores for all monitored countries
     */
    @Cacheable(value = "allRiskScores", sync = true)
    public List<RiskScore> getAllRiskScores() {
        log.info("Calculating risk scores for all monitored countries");

        List<RiskScore> scores = new ArrayList<>();

        for (String iso2 : ISO2_TO_ISO3.keySet()) {
            try {
                RiskScore score = calculateRiskScore(iso2);
                if (score != null) {
                    // Enrich with trend data
                    trendTrackingService.enrichWithTrend(score);
                    scores.add(score);
                }
                // Small delay to avoid overwhelming APIs
                Thread.sleep(100);
            } catch (Exception e) {
                log.warn("Error calculating risk score for {}: {}", iso2, e.getMessage());
            }
        }

        // Sort by score descending (highest risk first)
        scores.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));

        log.info("Calculated risk scores for {} countries", scores.size());
        return scores;
    }

    /**
     * Get high-risk countries (score >= 71)
     */
    public List<RiskScore> getHighRiskCountries() {
        return getAllRiskScores().stream()
                .filter(RiskScore::isHighRisk)
                .toList();
    }

    /**
     * Get countries with confirmed warnings (2-of-3 rule)
     */
    public List<RiskScore> getConfirmedWarnings() {
        return getAllRiskScores().stream()
                .filter(RiskScore::isConfirmed)
                .filter(s -> s.getScore() >= 51)
                .toList();
    }

    /**
     * Get summary statistics
     */
    public Map<String, Object> getRiskSummary() {
        List<RiskScore> scores = getAllRiskScores();

        Map<String, Long> byLevel = scores.stream()
                .collect(Collectors.groupingBy(RiskScore::getRiskLevel, Collectors.counting()));

        return Map.of(
                "total", scores.size(),
                "critical", byLevel.getOrDefault("CRITICAL", 0L),
                "alert", byLevel.getOrDefault("ALERT", 0L),
                "warning", byLevel.getOrDefault("WARNING", 0L),
                "watch", byLevel.getOrDefault("WATCH", 0L),
                "stable", byLevel.getOrDefault("STABLE", 0L),
                "confirmedWarnings", scores.stream().filter(RiskScore::isConfirmed).count()
        );
    }
}
