package com.crisismonitor.service;

import com.crisismonitor.model.RiskScore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegionService {

    private final RiskScoreService riskScoreService;
    private final RssService rssService;
    private final ObjectMapper objectMapper;

    private Map<String, String> countryToRegion = new HashMap<>();
    private Map<String, String> regionNames = new HashMap<>();
    private Map<String, List<String>> regionCountries = new HashMap<>();

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("data/region-mapping.json");
            JsonNode root = objectMapper.readTree(resource.getInputStream());
            JsonNode regions = root.get("regions");

            regions.fieldNames().forEachRemaining(regionCode -> {
                JsonNode region = regions.get(regionCode);
                String name = region.get("name").asText();
                regionNames.put(regionCode, name);

                List<String> countries = new ArrayList<>();
                region.get("countries").forEach(c -> {
                    String iso3 = c.asText();
                    countries.add(iso3);
                    countryToRegion.put(iso3, regionCode);
                });
                regionCountries.put(regionCode, countries);
            });

            log.info("Loaded region mapping: {} regions, {} countries",
                regionNames.size(), countryToRegion.size());
        } catch (IOException e) {
            log.error("Failed to load region mapping: {}", e.getMessage());
        }
    }

    public String getRegion(String iso3) {
        return countryToRegion.getOrDefault(iso3, "OTHER");
    }

    public String getRegionName(String regionCode) {
        return regionNames.getOrDefault(regionCode, regionCode);
    }

    public List<String> getRegionCountries(String regionCode) {
        return regionCountries.getOrDefault(regionCode, Collections.emptyList());
    }

    /**
     * Find the dominant driver for a region.
     * Uses the most common TOP driver among the highest-scoring countries,
     * with a conflict override only for active wars (conflictScore >= 50).
     */
    private String findMostCriticalDriver(List<RiskScore> topCountries, List<RiskScore> allRegionScores) {
        // Count the TOP driver (first driver = highest-scoring component) of the top 5 countries
        Map<String, Integer> topDriverCounts = new java.util.LinkedHashMap<>();
        List<RiskScore> top5 = allRegionScores.stream()
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                .limit(5)
                .collect(Collectors.toList());

        for (var country : top5) {
            if (country.getDrivers() != null && !country.getDrivers().isEmpty()) {
                String topDriver = country.getDrivers().get(0);
                topDriverCounts.merge(topDriver, 1, Integer::sum);
            }
        }

        // Active war override: if ANY country has conflictScore >= 50,
        // conflict is the dominant regional driver (catches wars where conflict
        // gets cut from a country's top-3 drivers by higher food/economic scores)
        for (var country : allRegionScores) {
            if (country.getConflictScore() >= 50) {
                return "Conflict";
            }
        }

        // Return the most frequent top driver
        return topDriverCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(topCountries.isEmpty() || topCountries.get(0).getDrivers() == null
                        || topCountries.get(0).getDrivers().isEmpty()
                        ? null : topCountries.get(0).getDrivers().get(0));
    }

    /**
     * Get Regional Pulse data for Overview
     */
    @Cacheable(value = "regionalPulseV2", unless = "#result == null")
    public RegionalPulseData getRegionalPulse() {
        log.info("Building Regional Pulse data...");
        long start = System.currentTimeMillis();

        RegionalPulseData pulse = new RegionalPulseData();
        pulse.setTimestamp(new Date());

        // Get all risk scores
        var allScores = riskScoreService.getAllRiskScores();
        if (allScores == null || allScores.isEmpty()) {
            log.warn("No risk scores available for Regional Pulse");
            return pulse;
        }

        // Group by region
        Map<String, List<RiskScore>> byRegion = allScores.stream()
            .collect(Collectors.groupingBy(s -> getRegion(s.getIso3())));

        // Build pulse for each region
        List<String> regionOrder = Arrays.asList("AFRICA", "MENA", "ASIA", "LAC", "EUROPE");

        for (String regionCode : regionOrder) {
            List<RiskScore> scores = byRegion.getOrDefault(regionCode, Collections.emptyList());

            RegionPulseCard card = new RegionPulseCard();
            card.setRegionCode(regionCode);
            card.setRegionName(getRegionName(regionCode));

            // Count critical and high
            int critical = 0, high = 0;
            for (var s : scores) {
                if ("CRITICAL".equals(s.getRiskLevel())) critical++;
                else if ("ALERT".equals(s.getRiskLevel()) || "HIGH".equals(s.getRiskLevel())) high++;
            }
            card.setCriticalCount(critical);
            card.setHighCount(high);

            // Top 2 hotspots
            List<RiskScore> sorted = scores.stream()
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                .limit(2)
                .collect(Collectors.toList());

            if (sorted.size() > 0) {
                var top = sorted.get(0);
                card.setHotspot1Iso3(top.getIso3());
                card.setHotspot1Name(top.getCountryName());
                card.setHotspot1Score(top.getScore());
                card.setHotspot1Level(top.getRiskLevel());

                // Dominant driver: pick the most critical driver type across top countries
                // Severity hierarchy: Conflict > Food Security > Economic > Climate
                String dominantDriver = findMostCriticalDriver(sorted, scores);

                card.setDominantDriver(dominantDriver);
            }

            if (sorted.size() > 1) {
                var second = sorted.get(1);
                card.setHotspot2Iso3(second.getIso3());
                card.setHotspot2Name(second.getCountryName());
                card.setHotspot2Score(second.getScore());
            }

            pulse.getRegions().add(card);
        }

        // Get primary focus (top overall)
        if (!allScores.isEmpty()) {
            var top = allScores.get(0);
            pulse.setPrimaryFocusIso3(top.getIso3());
            pulse.setPrimaryFocusName(top.getCountryName());
            pulse.setPrimaryFocusScore(top.getScore());
            pulse.setPrimaryFocusLevel(top.getRiskLevel());
            pulse.setPrimaryFocusDrivers(top.getDrivers());
            pulse.setPrimaryFocusHorizon(top.getHorizon());
        }

        long duration = System.currentTimeMillis() - start;
        log.info("Regional Pulse built in {}ms", duration);

        return pulse;
    }

    /**
     * Get context headlines for a region
     */
    @Cacheable(value = "regionContext", key = "#regionCode", unless = "#result == null || #result.isEmpty()")
    public List<ContextItem> getRegionContext(String regionCode) {
        List<ContextItem> items = new ArrayList<>();

        // Try to get RSS headlines for this region
        List<RssService.RssItem> headlines = rssService.getRegionHeadlines(regionCode, 3);

        for (RssService.RssItem item : headlines) {
            ContextItem ctx = new ContextItem();
            ctx.setType(item.isHumanitarian() ? "Humanitarian" : "Media");
            ctx.setRegion(regionCode);
            ctx.setTitle(item.getTitle());
            ctx.setSource(item.getSource());
            ctx.setTimestamp(item.getPubDate());
            ctx.setUrl(item.getLink());
            items.add(ctx);
        }

        return items;
    }

    /**
     * Get all context for Overview (all regions)
     */
    @Cacheable(value = "overviewContext", unless = "#result == null || #result.isEmpty()")
    public List<ContextItem> getOverviewContext() {
        List<ContextItem> all = new ArrayList<>();

        // Get 1 headline per region for balance
        for (String region : Arrays.asList("AFRICA", "MENA", "ASIA", "LAC", "EUROPE")) {
            List<ContextItem> regionItems = getRegionContext(region);
            if (!regionItems.isEmpty()) {
                all.add(regionItems.get(0));
            }
        }

        // If we don't have enough, add more from any region
        if (all.size() < 3) {
            for (String region : Arrays.asList("AFRICA", "MENA", "LAC")) {
                List<ContextItem> regionItems = getRegionContext(region);
                for (ContextItem item : regionItems) {
                    if (!all.contains(item) && all.size() < 5) {
                        all.add(item);
                    }
                }
            }
        }

        return all;
    }

    /**
     * Get full regional detail: all countries ranked by risk score with driver breakdown.
     */
    @Cacheable(value = "regionDetail", key = "#regionCode", unless = "#result == null")
    public RegionDetailData getRegionDetail(String regionCode) {
        log.info("Building Region Detail for {}", regionCode);

        RegionDetailData detail = new RegionDetailData();
        detail.setRegionCode(regionCode);
        detail.setRegionName(getRegionName(regionCode));

        var allScores = riskScoreService.getAllRiskScores();
        if (allScores == null || allScores.isEmpty()) return detail;

        // Filter to this region
        List<RiskScore> regionScores = allScores.stream()
                .filter(s -> regionCode.equals(getRegion(s.getIso3())))
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());

        // Countries
        List<RegionCountryRow> rows = regionScores.stream()
                .map(s -> {
                    RegionCountryRow row = new RegionCountryRow();
                    row.setIso3(s.getIso3());
                    row.setName(s.getCountryName());
                    row.setScore(s.getScore());
                    row.setRiskLevel(s.getRiskLevel());
                    row.setTrend(s.getTrend());
                    row.setTrendIcon(s.getTrendIcon());
                    row.setScoreDelta(s.getScoreDelta());
                    row.setTopDriver(s.getDrivers() != null && !s.getDrivers().isEmpty() ? s.getDrivers().get(0) : null);
                    return row;
                })
                .collect(Collectors.toList());
        detail.setCountries(rows);

        // Counts
        detail.setCriticalCount((int) regionScores.stream().filter(s -> "CRITICAL".equals(s.getRiskLevel())).count());
        detail.setAlertCount((int) regionScores.stream().filter(s -> "ALERT".equals(s.getRiskLevel())).count());
        detail.setWarningCount((int) regionScores.stream().filter(s -> "WARNING".equals(s.getRiskLevel())).count());

        // Dominant drivers across region
        Map<String, Long> driverCounts = regionScores.stream()
                .filter(s -> s.getDrivers() != null)
                .flatMap(s -> s.getDrivers().stream())
                .collect(Collectors.groupingBy(d -> d, Collectors.counting()));

        List<Map.Entry<String, Long>> sortedDrivers = driverCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(4)
                .collect(Collectors.toList());

        List<RegionDriverStat> driverStats = sortedDrivers.stream()
                .map(e -> {
                    RegionDriverStat stat = new RegionDriverStat();
                    stat.setDriver(e.getKey());
                    stat.setCount(e.getValue().intValue());
                    stat.setPercent(regionScores.isEmpty() ? 0 : Math.round(100.0 * e.getValue() / regionScores.size()));
                    return stat;
                })
                .collect(Collectors.toList());
        detail.setDriverMix(driverStats);

        // Average score
        detail.setAvgScore((int) Math.round(regionScores.stream().mapToInt(RiskScore::getScore).average().orElse(0)));

        return detail;
    }

    // ========================================
    // DTOs
    // ========================================

    @Data
    public static class RegionalPulseData {
        private Date timestamp;
        private String primaryFocusIso3;
        private String primaryFocusName;
        private int primaryFocusScore;
        private String primaryFocusLevel;
        private List<String> primaryFocusDrivers;
        private String primaryFocusHorizon;
        private List<RegionPulseCard> regions = new ArrayList<>();
    }

    @Data
    public static class RegionPulseCard {
        private String regionCode;
        private String regionName;
        private int criticalCount;
        private int highCount;
        private String hotspot1Iso3;
        private String hotspot1Name;
        private int hotspot1Score;
        private String hotspot1Level;
        private String hotspot2Iso3;
        private String hotspot2Name;
        private int hotspot2Score;
        private String dominantDriver;
    }

    @Data
    public static class ContextItem {
        private String type; // "Humanitarian" or "Media"
        private String region;
        private String title;
        private String source;
        private String timestamp;
        private String url;
    }

    @Data
    public static class RegionDetailData {
        private String regionCode;
        private String regionName;
        private int criticalCount;
        private int alertCount;
        private int warningCount;
        private int avgScore;
        private List<RegionCountryRow> countries = new ArrayList<>();
        private List<RegionDriverStat> driverMix = new ArrayList<>();
    }

    @Data
    public static class RegionCountryRow {
        private String iso3;
        private String name;
        private int score;
        private String riskLevel;
        private String trend;
        private String trendIcon;
        private Integer scoreDelta;
        private String topDriver;
    }

    @Data
    public static class RegionDriverStat {
        private String driver;
        private int count;
        private long percent;
    }
}
