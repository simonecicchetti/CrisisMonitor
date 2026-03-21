package com.crisismonitor.service;

import com.crisismonitor.model.MediaSpike;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cache warmup service - preloads expensive caches on startup
 * and refreshes them periodically BEFORE they expire.
 *
 * Strategy:
 * Phase 1 (~30s): Climate, Currency, Food Security, WHO, ReliefWeb → Risk Scores → Intelligence Feed
 * Phase 2 (background, ~15min): GDELT → Re-calc Risk Scores → Active Situations
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheWarmupService {

    private final RiskScoreService riskScoreService;
    private final OpenMeteoService openMeteoService;
    private final GDELTService gdeltService;
    private final CurrencyService currencyService;
    private final FewsNetService fewsNetService;
    private final CacheManager cacheManager;
    private final IntelligenceFeedService intelligenceFeedService;
    private final NewsAggregatorService newsAggregatorService;
    private final SituationDetectionService situationDetectionService;
    private final WHODiseaseOutbreakService whoDiseaseOutbreakService;
    private final ReliefWebService reliefWebService;
    private final ClimateService climateService;
    private final DTMService dtmService;
    private final RssService rssService;
    private final WorldBankService worldBankService;

    // Track warmup status
    private final AtomicBoolean warmupComplete = new AtomicBoolean(false);
    private final Map<String, Boolean> cacheStatus = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastRefresh = new ConcurrentHashMap<>();

    // In-memory fallback (survives cache eviction)
    private final Map<String, Object> memoryFallback = new ConcurrentHashMap<>();

    public Map<String, Object> getWarmupStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("complete", warmupComplete.get());
        status.put("caches", new HashMap<>(cacheStatus));
        status.put("lastRefresh", new HashMap<>(lastRefresh));
        return status;
    }

    public boolean isCacheReady(String cacheName) {
        return Boolean.TRUE.equals(cacheStatus.get(cacheName));
    }

    public boolean isAllReady() {
        return warmupComplete.get();
    }

    @SuppressWarnings("unchecked")
    public <T> T getFallback(String key) {
        return (T) memoryFallback.get(key);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmupOnStartup() {
        log.info("=== Starting cache warmup ===");
        long startTime = System.currentTimeMillis();

        warmupComplete.set(false);

        // Preserve expensive GDELT data across deploys (takes hours to rebuild).
        // Preload from Redis into memoryFallback so conflict scores work immediately.
        preloadExpensiveDataFromRedis();

        // Evict cheap-to-rebuild caches (everything except GDELT + trend history)
        evictCheapCaches();

        // Phase 1a: Fast data APIs (NDVI from static fallback, Currency, Food Security)
        // NDVI must be ready BEFORE risk scores (used for climate calibration)
        // Climate (precipitation anomalies, 276+ OpenMeteo calls) is SLOW — runs in background
        CompletableFuture<Void> ndviFuture = warmupNDVI();
        CompletableFuture<Void> currencyFuture = warmupCurrency();
        CompletableFuture<Void> foodSecurityFuture = warmupFoodSecurity();

        // Phase 1b: Web/RSS sources + Risk Scores once fast APIs complete
        CompletableFuture.allOf(ndviFuture, currencyFuture, foodSecurityFuture)
                .thenCompose(v -> {
                    log.info("Phase 1a (fast data APIs) complete, starting Phase 1b (web sources)...");
                    CompletableFuture<Void> whoFuture = warmupWHO();
                    CompletableFuture<Void> briefingFuture = CompletableFuture.runAsync(this::warmupDailyBriefing);
                    CompletableFuture<Void> dtmFuture = CompletableFuture.runAsync(this::warmupDTM);
                    CompletableFuture<Void> newsFuture = CompletableFuture.runAsync(this::warmupNewsHeadlines);
                    CompletableFuture<Void> wbSfiFuture = CompletableFuture.runAsync(() -> {
                        try {
                            var sfi = worldBankService.getSevereFoodInsecurity();
                            if (sfi != null && !sfi.isEmpty()) {
                                memoryFallback.put("worldBankSFI", sfi);
                                log.info("World Bank SFI warmed: {} countries", sfi.size());
                            }
                        } catch (Exception e) {
                            log.debug("World Bank SFI warmup failed: {}", e.getMessage());
                        }
                    });
                    return CompletableFuture.allOf(whoFuture, briefingFuture, dtmFuture, newsFuture, wbSfiFuture);
                })
                .thenRun(() -> {
                    warmupRiskScores();
                    warmupIntelligenceFeed(); // Needs risk scores

                    long duration = System.currentTimeMillis() - startTime;
                    warmupComplete.set(true);
                    log.info("=== Phase 1 warmup complete in {}s ===", duration / 1000);
                })
                .exceptionally(e -> {
                    log.error("Phase 1 warmup failed: {}", e.getMessage());
                    warmupComplete.set(true);
                    return null;
                });

        // Phase 1.5: Climate (precipitation anomalies) in background — slow, recalculates risk scores when done
        CompletableFuture.runAsync(() -> {
            try {
                warmupClimate().join();
                log.info("Climate warmup complete, recalculating risk scores with precipitation data...");
                evictCache("allRiskScores");
                evictCache("riskScore");
                memoryFallback.remove("allRiskScores");
                warmupRiskScores();
                evictCache("regionalPulseV2");
                memoryFallback.remove("regionalPulse");
            } catch (Exception e) {
                log.error("Climate background warmup failed: {}", e.getMessage());
            }
        });

        // Phase 2: GDELT in background (slow, 15s rate limit per call)
        CompletableFuture.runAsync(() -> {
            try {
                log.info("Phase 2: Starting GDELT warmup in background...");
                warmupConflict().join();

                // Re-calculate everything with GDELT data
                evictCache("allRiskScores");
                evictCache("riskScore");
                memoryFallback.remove("allRiskScores");
                warmupRiskScores();

                evictCache("intelligenceFeed");
                memoryFallback.remove("intelligenceFeed");
                warmupIntelligenceFeed();

                evictCache("activeSituationsV2");
                memoryFallback.remove("activeSituations");
                warmupActiveSituations();

                // Regional Pulse depends on risk scores — must recalculate after GDELT changes drivers
                evictCache("regionalPulseV2");
                memoryFallback.remove("regionalPulse");

                long duration = System.currentTimeMillis() - startTime;
                log.info("=== Phase 2 complete in {}s (full data with GDELT) ===", duration / 1000);
            } catch (Exception e) {
                log.error("Phase 2 (GDELT) warmup failed: {}", e.getMessage());
            }
        });
    }

    @Async
    public CompletableFuture<Void> warmupClimate() {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Warming up climate cache...");
                var data = openMeteoService.getAllPrecipitationAnomalies();
                if (data != null && !data.isEmpty()) {
                    memoryFallback.put("allPrecipAnomalies", data);
                    cacheStatus.put("climate", true);
                    lastRefresh.put("climate", LocalDateTime.now());
                    log.info("Climate cache warmed: {} entries", data.size());
                } else {
                    log.warn("Climate returned empty data, keeping previous fallback");
                }
            } catch (Exception e) {
                log.error("Climate warmup failed: {}", e.getMessage());
                cacheStatus.put("climate", false);
            }
        });
    }

    @Async
    public CompletableFuture<Void> warmupCurrency() {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Warming up currency cache...");
                var data = currencyService.getAllCurrencyData();
                memoryFallback.put("allCurrencyData", data);
                cacheStatus.put("currency", true);
                lastRefresh.put("currency", LocalDateTime.now());
                log.info("Currency cache warmed: {} entries", data != null ? data.size() : 0);
            } catch (Exception e) {
                log.error("Currency warmup failed: {}", e.getMessage());
                cacheStatus.put("currency", false);
            }
        });
    }

    @Async
    public CompletableFuture<Void> warmupConflict() {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Warming up conflict cache (GDELT)...");
                var data = gdeltService.getAllConflictSpikes();
                memoryFallback.put("gdeltAllSpikes", data);
                cacheStatus.put("conflict", true);
                lastRefresh.put("conflict", LocalDateTime.now());
                log.info("Conflict cache warmed: {} entries", data != null ? data.size() : 0);
            } catch (Exception e) {
                log.error("Conflict warmup failed: {}", e.getMessage());
                cacheStatus.put("conflict", false);
            }
        });
    }

    @Async
    public CompletableFuture<Void> warmupFoodSecurity() {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Warming up food security cache...");
                var data = fewsNetService.getAllIPCAlerts();
                memoryFallback.put("fewsAllIPC", data);
                cacheStatus.put("foodSecurity", true);
                lastRefresh.put("foodSecurity", LocalDateTime.now());
                log.info("Food security cache warmed: {} entries", data != null ? data.size() : 0);
            } catch (Exception e) {
                log.error("Food security warmup failed: {}", e.getMessage());
                cacheStatus.put("foodSecurity", false);
            }
        });
    }

    @Async
    private void warmupDTM() {
        log.info("Warming up DTM (IDP) cache...");
        try {
            var data = dtmService.getCountryLevelIdps();
            if (data != null && !data.isEmpty()) {
                memoryFallback.put("dtmData", data);
                cacheStatus.put("dtm", true);
                lastRefresh.put("dtm", LocalDateTime.now());
                log.info("DTM cache warmed: {} countries", data.size());
            } else {
                log.warn("DTM returned empty data");
                cacheStatus.put("dtm", false);
            }
        } catch (Throwable e) {
            log.error("DTM warmup failed: {}", e.getMessage());
            cacheStatus.put("dtm", false);
        }
    }

    public CompletableFuture<Void> warmupWHO() {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Warming up WHO Disease Outbreaks cache...");
                var data = whoDiseaseOutbreakService.getRecentOutbreaks();
                memoryFallback.put("whoDiseaseOutbreaks", data);
                cacheStatus.put("who", true);
                lastRefresh.put("who", LocalDateTime.now());
                log.info("WHO cache warmed: {} entries", data != null ? data.size() : 0);
            } catch (Exception e) {
                log.error("WHO warmup failed: {}", e.getMessage());
                cacheStatus.put("who", false);
            }
        });
    }

    @Async
    public CompletableFuture<Void> warmupNDVI() {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Warming up NDVI climate data...");
                var data = climateService.getClimateAnomalies();
                if (data != null && !data.isEmpty()) {
                    memoryFallback.put("ndviClimateData", data);
                    // Also store filtered stress data for Drivers tab (NDVI < 0.9)
                    var stressed = data.stream()
                            .filter(c -> c.getNdviAnomaly() != null && c.getNdviAnomaly() < 0.9)
                            .sorted((a, b) -> Double.compare(
                                    a.getNdviAnomaly() != null ? a.getNdviAnomaly() : 1.0,
                                    b.getNdviAnomaly() != null ? b.getNdviAnomaly() : 1.0))
                            .toList();
                    memoryFallback.put("climateStress", stressed);
                    cacheStatus.put("ndvi", true);
                    lastRefresh.put("ndvi", LocalDateTime.now());
                    log.info("NDVI cache warmed: {} entries ({} stressed)", data.size(), stressed.size());
                } else {
                    log.warn("NDVI returned empty data, keeping previous fallback ({} entries)",
                            memoryFallback.containsKey("ndviClimateData") ? ((java.util.Collection<?>) memoryFallback.get("ndviClimateData")).size() : 0);
                }
            } catch (Exception e) {
                log.error("NDVI warmup failed: {}", e.getMessage());
                cacheStatus.put("ndvi", false);
            }
        });
    }

    private void warmupRiskScores() {
        try {
            log.info("Warming up risk score cache...");
            var data = riskScoreService.getAllRiskScores();
            memoryFallback.put("allRiskScores", data);
            cacheStatus.put("riskScores", true);
            lastRefresh.put("riskScores", LocalDateTime.now());
            log.info("Risk score cache warmed: {} entries", data != null ? data.size() : 0);
        } catch (Exception e) {
            log.error("Risk score warmup failed: {}", e.getMessage());
            cacheStatus.put("riskScores", false);
        }
    }

    private void warmupIntelligenceFeed() {
        try {
            log.info("Warming up Intelligence Feed cache...");
            evictCache("intelligenceFeed");
            var data = intelligenceFeedService.getIntelligenceFeed();
            memoryFallback.put("intelligenceFeed", data);
            cacheStatus.put("intelligenceFeed", true);
            lastRefresh.put("intelligenceFeed", LocalDateTime.now());
            log.info("Intelligence Feed cache warmed");
        } catch (Exception e) {
            log.error("Intelligence Feed warmup failed: {}", e.getMessage());
            cacheStatus.put("intelligenceFeed", false);
        }
    }

    private void warmupActiveSituations() {
        try {
            log.info("Warming up Active Situations cache...");
            evictCache("activeSituationsV2");
            var data = situationDetectionService.getActiveSituations();
            memoryFallback.put("activeSituations", data);
            cacheStatus.put("activeSituations", true);
            lastRefresh.put("activeSituations", LocalDateTime.now());
            log.info("Active Situations cache warmed: {} situations", data.getTotalSituations());
        } catch (Exception e) {
            log.error("Active Situations warmup failed: {}", e.getMessage());
            cacheStatus.put("activeSituations", false);
        }
    }

    private void warmupNewsHeadlines() {
        try {
            log.info("Warming up news headlines for intelligence reports...");
            List<Map<String, String>> allHeadlines = new ArrayList<>();

            // RSS headlines per region
            for (String region : List.of("AFRICA", "MENA", "ASIA", "LAC", "EUROPE")) {
                try {
                    var items = rssService.getRegionHeadlines(region, 10); // More headlines for better country matching
                    if (items != null) {
                        for (var item : items) {
                            Map<String, String> h = new HashMap<>();
                            h.put("title", item.getTitle());
                            h.put("source", item.getSource());
                            h.put("region", region);
                            h.put("type", item.isHumanitarian() ? "ReliefWeb" : "Media");
                            if (item.getLink() != null) h.put("url", item.getLink());
                            allHeadlines.add(h);
                        }
                    }
                } catch (Exception e) {
                    log.debug("RSS warmup for {}: {}", region, e.getMessage());
                }
            }

            // ReliefWeb latest reports (top countries)
            // Fetch ReliefWeb reports for ALL monitored countries (not just a subset)
            for (String iso3 : com.crisismonitor.config.MonitoredCountries.CRISIS_COUNTRIES) {
                try {
                    var reports = reliefWebService.getLatestReports(iso3, 3, 7);
                    if (reports != null) {
                        for (var r : reports) {
                            Map<String, String> h = new HashMap<>();
                            h.put("title", r.getTitle());
                            h.put("source", r.getSource() != null ? r.getSource() : "ReliefWeb");
                            h.put("iso3", iso3);
                            h.put("type", "ReliefWeb");
                            if (r.getUrl() != null) h.put("url", r.getUrl());
                            if (r.getDate() != null) h.put("date", r.getDate());
                            allHeadlines.add(h);
                        }
                    }
                    Thread.sleep(100); // gentle rate limit
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.debug("ReliefWeb warmup for {}: {}", iso3, e.getMessage());
                }
            }

            memoryFallback.put("newsHeadlines", allHeadlines);
            log.info("News headlines warmed: {} total (RSS + ReliefWeb)", allHeadlines.size());
        } catch (Exception e) {
            log.error("News headlines warmup failed: {}", e.getMessage());
        }
    }

    private void warmupDailyBriefing() {
        try {
            log.info("Warming up Daily Briefing cache...");
            evictCache("dailyBriefing");
            var data = newsAggregatorService.getDailyBriefing();
            memoryFallback.put("dailyBriefing", data);
            cacheStatus.put("dailyBriefing", true);
            lastRefresh.put("dailyBriefing", LocalDateTime.now());
            log.info("Daily Briefing cache warmed: {} items", data.getTotalItems());
        } catch (Exception e) {
            log.error("Daily Briefing warmup failed: {}", e.getMessage());
            cacheStatus.put("dailyBriefing", false);
        }
    }

    // === Scheduled Refreshes ===

    /**
     * Refresh GDELT every 3 hours (TTL is 4h, refresh before expiry)
     */
    @Scheduled(fixedRate = 3 * 60 * 60 * 1000, initialDelay = 3 * 60 * 60 * 1000)
    public void refreshGdeltCaches() {
        log.info("Scheduled refresh for GDELT caches...");
        evictCache("gdeltAllSpikes");
        evictCache("gdeltSpikeIndex");
        evictCache("gdeltConflictCount");

        CompletableFuture.runAsync(() -> {
            warmupConflict().join();
            evictCache("allRiskScores");
            evictCache("riskScore");
            warmupRiskScores();
            evictCache("activeSituationsV2");
            warmupActiveSituations();
            evictCache("intelligenceFeed");
            warmupIntelligenceFeed();
            evictCache("regionalPulseV2");
            memoryFallback.remove("regionalPulse");
        });
    }

    @Scheduled(fixedRate = 30 * 60 * 1000, initialDelay = 30 * 60 * 1000)
    public void refreshFoodSecurityCaches() {
        log.info("Scheduled refresh for food security caches...");
        evictCache("fewsAllIPC");
        warmupFoodSecurity();
    }

    @Scheduled(fixedRate = 55 * 60 * 1000, initialDelay = 55 * 60 * 1000)
    public void refreshLongTtlCaches() {
        log.info("Scheduled refresh for long-TTL caches (climate, currency, NDVI, risk)...");
        evictCache("allPrecipAnomalies");
        evictCache("precipAnomaly");
        evictCache("climateData");
        evictCache("allCurrencyData");
        evictCache("currencyData");
        evictCache("exchangeRates");
        evictCache("allRiskScores");

        CompletableFuture<Void> ndviFuture = warmupNDVI();
        CompletableFuture<Void> climateFuture = warmupClimate();
        CompletableFuture<Void> currencyFuture = warmupCurrency();

        CompletableFuture.allOf(ndviFuture, climateFuture, currencyFuture)
                .thenRun(this::warmupRiskScores);
    }

    @Scheduled(fixedRate = 45 * 60 * 1000, initialDelay = 45 * 60 * 1000)
    public void refreshIntelligenceAndBriefing() {
        log.info("Scheduled refresh for Intelligence, Situations, Briefing, and News...");
        evictCache("intelligenceFeed");
        evictCache("activeSituationsV2");
        evictCache("dailyBriefing");
        evictCache("whoDiseaseOutbreaks");
        evictCache("newsFeed");
        evictCache("storiesV16");
        warmupIntelligenceFeed();
        warmupActiveSituations();
        warmupDailyBriefing();
        warmupNewsHeadlines();
        CompletableFuture.runAsync(() -> {
            try {
                warmupWHO().join();
            } catch (Exception e) {
                log.warn("WHO refresh failed: {}", e.getMessage());
            }
        });
    }

    // Explicit list of ALL known cache names — cacheManager.getCacheNames() only returns
    // caches created in THIS JVM instance, so old Redis keys from previous deploys survive.
    // This list ensures every deploy starts with a completely clean Redis state.
    private static final List<String> ALL_KNOWN_CACHES = List.of(
            // Risk scores
            "riskScore", "allRiskScores",
            // Climate & weather
            "allPrecipAnomalies", "precipAnomaly", "climateData",
            // GDELT
            "gdeltAllSpikes", "gdeltSpikeIndex", "gdeltConflictCount",
            "gdeltHeadlines", "gdeltHeadlinesWithUrl", "gdeltTone",
            "gdeltGeoEvents", "gdeltCrisisVolume", "gdeltRegionHeadlines",
            // Currency
            "allCurrencyData", "currencyData", "exchangeRates",
            // Food security
            "fewsAllIPC", "fewsIPC", "foodSecurityMetrics",
            // Intelligence & situations
            "intelligenceFeed", "activeSituationsV2", "dailyBriefing",
            "topicIntelligence",
            // AI analysis
            "aiAnalysisGlobal", "aiAnalysisCountry", "aiAnalysisRegion",
            "aiAnalysisRegionV2", "claudeSituations",
            // Regional
            "regionalPulseV2", "regionContext", "overviewContext", "regionDetail",
            "countryProfileAggregated", "countryDataPack",
            // WHO & ReliefWeb
            "whoDiseaseOutbreaks", "reliefwebReports", "reliefwebHeadlines",
            "reliefwebDisasters",
            // News feed, stories & RSS
            "newsFeed", "storiesV16", "rssGlobal", "rssHeadlines",
            // DTM (IOM displacement)
            "dtmCountryData", "dtmByReason", "dtmOperations",
            // HungerMap
            "countries", "ipcData", "severityData", "alerts", "foodSecurityMetrics",
            // WorldBank
            "countryProfiles", "inflationData", "gdpData", "populationData", "povertyData",
            // UNHCR
            "unhcrPopulation", "unhcrGlobalSummary", "unhcrAsylum",
            "unhcrDemographics", "unhcrSolutions",
            // Other
            "hazards", "migrationData",
            // Trend tracking
            "trendHistory"
    );

    /**
     * Preload expensive-to-rebuild data from Redis into memoryFallback.
     * GDELT takes hours to fetch — preserving it across deploys means
     * conflict scores are non-zero from the very first risk score calculation.
     */
    @SuppressWarnings("unchecked")
    private void preloadExpensiveDataFromRedis() {
        log.info("Attempting to preload GDELT data from Redis...");
        try {
            // Use a timeout to prevent blocking warmup if Redis is slow
            var future = CompletableFuture.supplyAsync(() -> {
                try {
                    Cache gdeltCache = cacheManager.getCache("gdeltAllSpikes");
                    if (gdeltCache == null) return null;
                    Cache.ValueWrapper wrapper = gdeltCache.get(org.springframework.cache.interceptor.SimpleKey.EMPTY);
                    if (wrapper == null || wrapper.get() == null) return null;
                    return (java.util.List<MediaSpike>) wrapper.get();
                } catch (Exception e) {
                    log.warn("Failed to read GDELT from Redis: {}", e.getMessage());
                    return null;
                }
            });

            var data = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
            if (data != null && !data.isEmpty()) {
                memoryFallback.put("gdeltAllSpikes", data);
                cacheStatus.put("conflict", true);
                log.info("Preloaded {} GDELT entries from Redis (preserved across deploy)", data.size());
            } else {
                log.info("No existing GDELT data in Redis to preload");
            }
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("GDELT preload from Redis timed out after 15s, skipping");
        } catch (Exception e) {
            log.warn("GDELT preload failed: {}", e.getMessage());
        }
    }

    /**
     * Evict cheap-to-rebuild caches but preserve expensive ones (GDELT, trend history).
     * GDELT data takes hours to rebuild — Phase 2 will replace it with fresh data.
     * Trend history tracks changes over time and should never be wiped.
     */
    private void evictCheapCaches() {
        log.info("Evicting cheap-to-rebuild caches (preserving GDELT + trends)...");
        // Quick test: try one cache first. If Redis is down, skip all eviction.
        try {
            Cache testCache = cacheManager.getCache("riskScore");
            if (testCache != null) {
                testCache.clear();
            }
        } catch (Exception e) {
            log.warn("Redis unavailable, skipping cache eviction: {}", e.getMessage());
            return;
        }

        // Expensive caches to preserve across deploys
        var preservedCaches = java.util.Set.of(
                "gdeltAllSpikes", "gdeltSpikeIndex", "gdeltConflictCount",
                "gdeltHeadlines", "gdeltHeadlinesWithUrl", "gdeltTone",
                "gdeltGeoEvents", "gdeltCrisisVolume", "gdeltRegionHeadlines",
                "trendHistory"
        );

        int evicted = 0;
        for (String cacheName : ALL_KNOWN_CACHES) {
            if (!preservedCaches.contains(cacheName)) {
                evictCache(cacheName);
                evicted++;
            }
        }
        log.info("Evicted {} caches, preserved {} expensive caches", evicted, preservedCaches.size());
    }

    private void evictCache(String cacheName) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
                log.debug("Evicted cache: {}", cacheName);
            }
        } catch (Exception e) {
            log.warn("Failed to evict cache {}: {}", cacheName, e.getMessage());
        }
    }
}
