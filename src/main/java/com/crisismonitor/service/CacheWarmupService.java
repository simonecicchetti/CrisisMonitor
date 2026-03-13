package com.crisismonitor.service;

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
import java.util.HashMap;
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

        memoryFallback.clear();
        cacheStatus.clear();
        warmupComplete.set(false);

        // Evict all Redis caches on startup to force fresh data with updated country lists
        evictAllCaches();

        // Phase 1a: Data APIs first (climate is 276+ calls, needs dedicated bandwidth)
        CompletableFuture<Void> climateFuture = warmupClimate();
        CompletableFuture<Void> currencyFuture = warmupCurrency();
        CompletableFuture<Void> foodSecurityFuture = warmupFoodSecurity();

        // Phase 1b: Web/RSS sources AFTER data APIs (avoids network congestion)
        CompletableFuture.allOf(climateFuture, currencyFuture, foodSecurityFuture)
                .thenCompose(v -> {
                    log.info("Phase 1a (data APIs) complete, starting Phase 1b (web sources)...");
                    CompletableFuture<Void> whoFuture = warmupWHO();
                    CompletableFuture<Void> briefingFuture = CompletableFuture.runAsync(this::warmupDailyBriefing);
                    return CompletableFuture.allOf(whoFuture, briefingFuture);
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
                memoryFallback.put("allPrecipAnomalies", data);
                cacheStatus.put("climate", true);
                lastRefresh.put("climate", LocalDateTime.now());
                log.info("Climate cache warmed: {} entries", data != null ? data.size() : 0);
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
        log.info("Scheduled refresh for long-TTL caches (climate, currency, risk)...");
        evictCache("allPrecipAnomalies");
        evictCache("precipAnomaly");
        evictCache("allCurrencyData");
        evictCache("currencyData");
        evictCache("exchangeRates");
        evictCache("allRiskScores");

        CompletableFuture<Void> climateFuture = warmupClimate();
        CompletableFuture<Void> currencyFuture = warmupCurrency();

        CompletableFuture.allOf(climateFuture, currencyFuture)
                .thenRun(this::warmupRiskScores);
    }

    @Scheduled(fixedRate = 45 * 60 * 1000, initialDelay = 45 * 60 * 1000)
    public void refreshIntelligenceAndBriefing() {
        log.info("Scheduled refresh for Intelligence, Situations, and Briefing...");
        evictCache("intelligenceFeed");
        evictCache("activeSituationsV2");
        evictCache("dailyBriefing");
        evictCache("whoDiseaseOutbreaks");
        warmupIntelligenceFeed();
        warmupActiveSituations();
        warmupDailyBriefing();
        CompletableFuture.runAsync(() -> {
            try {
                warmupWHO().join();
            } catch (Exception e) {
                log.warn("WHO refresh failed: {}", e.getMessage());
            }
        });
    }

    private void evictAllCaches() {
        log.info("Evicting all Redis caches for fresh startup...");
        for (String cacheName : cacheManager.getCacheNames()) {
            evictCache(cacheName);
        }
        log.info("All caches evicted");
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
