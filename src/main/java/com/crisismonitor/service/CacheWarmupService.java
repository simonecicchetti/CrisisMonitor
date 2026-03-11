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
 * 1. Warm up all caches on startup (async but tracked)
 * 2. In-memory fallback for each cache
 * 3. Proactive refresh at 50% of TTL (not waiting for expiry)
 * 4. Expose readiness status for frontend polling
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

    // Track warmup status
    private final AtomicBoolean warmupComplete = new AtomicBoolean(false);
    private final Map<String, Boolean> cacheStatus = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastRefresh = new ConcurrentHashMap<>();

    // In-memory fallback (survives cache eviction)
    private final Map<String, Object> memoryFallback = new ConcurrentHashMap<>();

    /**
     * Get warmup status for all caches
     */
    public Map<String, Object> getWarmupStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("complete", warmupComplete.get());
        status.put("caches", new HashMap<>(cacheStatus));
        status.put("lastRefresh", new HashMap<>(lastRefresh));
        return status;
    }

    /**
     * Check if a specific cache is ready
     */
    public boolean isCacheReady(String cacheName) {
        return Boolean.TRUE.equals(cacheStatus.get(cacheName));
    }

    /**
     * Check if all main caches are ready
     */
    public boolean isAllReady() {
        return warmupComplete.get();
    }

    /**
     * Get in-memory fallback data
     */
    @SuppressWarnings("unchecked")
    public <T> T getFallback(String key) {
        return (T) memoryFallback.get(key);
    }

    /**
     * Warm up caches after application starts
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmupOnStartup() {
        log.info("=== Starting cache warmup ===");
        long startTime = System.currentTimeMillis();

        // Clear stale in-memory fallback to avoid ClassCastException with DevTools restart
        memoryFallback.clear();
        cacheStatus.clear();
        warmupComplete.set(false);
        log.info("Cleared stale fallback cache");

        // Warm up each cache independently (parallel where possible)
        CompletableFuture<Void> climateFuture = warmupClimate();
        CompletableFuture<Void> currencyFuture = warmupCurrency();
        CompletableFuture<Void> conflictFuture = warmupConflict();
        CompletableFuture<Void> foodSecurityFuture = warmupFoodSecurity();

        // Start IntelligenceFeed and DailyBriefing IMMEDIATELY - they don't need GDELT
        // These use ReliefWeb and RSS feeds which are fast
        CompletableFuture.runAsync(() -> {
            warmupIntelligenceFeed();  // Fast - uses ReliefWeb, skips GDELT if not ready
            warmupDailyBriefing();     // Fast - uses RSS feeds and ReliefWeb API
            log.info("Intelligence Feed and Daily Briefing warmed up (GDELT-independent)");
        });

        // Risk scores need climate + currency + conflict + food security
        // Active Situations needs GDELT spikes
        CompletableFuture.allOf(climateFuture, currencyFuture, conflictFuture, foodSecurityFuture)
                .thenRun(() -> {
                    warmupRiskScores();
                    warmupActiveSituations(); // Needs GDELT data

                    long duration = System.currentTimeMillis() - startTime;
                    warmupComplete.set(true);
                    log.info("=== Cache warmup complete in {}s ===", duration / 1000);
                })
                .exceptionally(e -> {
                    log.error("Cache warmup failed: {}", e.getMessage());
                    warmupComplete.set(true); // Mark complete even on failure
                    return null;
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
            // Clear stale cache first to avoid deserialization issues
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

    /**
     * Proactive refresh at 30 minutes (before 1-hour GDELT/FEWS TTL expires)
     * and at 55 minutes (before 2-hour Risk/Climate/Currency TTL expires)
     */
    @Scheduled(fixedRate = 30 * 60 * 1000, initialDelay = 30 * 60 * 1000)
    public void refreshShortTtlCaches() {
        log.info("Scheduled refresh for short-TTL caches (conflict, food security)...");

        // Evict before refresh to force new fetch
        evictCache("gdeltAllSpikes");
        evictCache("fewsAllIPC");

        warmupConflict();
        warmupFoodSecurity();
    }

    @Scheduled(fixedRate = 55 * 60 * 1000, initialDelay = 55 * 60 * 1000)
    public void refreshLongTtlCaches() {
        log.info("Scheduled refresh for long-TTL caches (climate, currency, risk)...");

        // Evict before refresh
        evictCache("allPrecipAnomalies");
        evictCache("allCurrencyData");
        evictCache("allRiskScores");

        CompletableFuture<Void> climateFuture = warmupClimate();
        CompletableFuture<Void> currencyFuture = warmupCurrency();

        CompletableFuture.allOf(climateFuture, currencyFuture)
                .thenRun(this::warmupRiskScores);
    }

    /**
     * Refresh Intelligence Feed, Situations, and Daily Briefing every 45 minutes
     * This ensures news data is always ready and fast for users
     */
    @Scheduled(fixedRate = 45 * 60 * 1000, initialDelay = 45 * 60 * 1000)
    public void refreshIntelligenceAndBriefing() {
        log.info("Scheduled refresh for Intelligence, Situations, and Briefing...");
        evictCache("intelligenceFeed");
        evictCache("activeSituationsV2");
        evictCache("dailyBriefing");
        warmupIntelligenceFeed();
        warmupActiveSituations();
        warmupDailyBriefing();
    }

    /**
     * Evict a specific cache entry
     */
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
