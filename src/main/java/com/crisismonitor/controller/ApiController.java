package com.crisismonitor.controller;

import com.crisismonitor.config.MonitoredCountries;
import com.crisismonitor.model.*;
import com.crisismonitor.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.*;

/**
 * REST API endpoints for map and AJAX calls
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final DashboardService dashboardService;
    private final ClimateService climateService;
    private final MigrationService migrationService;
    private final HungerMapService hungerMapService;
    private final WorldBankService worldBankService;
    private final UNHCRService unhcrService;
    private final DTMService dtmService;
    private final GDELTService gdeltService;
    private final FewsNetService fewsNetService;
    private final OpenMeteoService openMeteoService;
    private final CurrencyService currencyService;
    private final RiskScoreService riskScoreService;
    private final AnalysisService analysisService;
    private final CacheManager cacheManager;
    private final CacheWarmupService cacheWarmupService;
    private final DataFreshnessService dataFreshnessService;
    private final RegionalClusterService regionalClusterService;
    private final TrendTrackingService trendTrackingService;
    private final ReliefWebService reliefWebService;
    private final TopicIntelligenceService topicIntelligenceService;
    private final IntelligenceFeedService intelligenceFeedService;
    private final NewsAggregatorService newsAggregatorService;
    private final SituationDetectionService situationDetectionService;
    private final TopicReportService topicReportService;
    private final StoryService storyService;
    private final RegionService regionService;
    private final CountryProfileService countryProfileService;
    private final WHODiseaseOutbreakService whoDiseaseOutbreakService;
    private final StructuralIndexService structuralIndexService;
    private final IntelligencePrepService intelligencePrepService;
    private final NowcastService nowcastService;
    private final ReportSchedulerService reportSchedulerService;
    private final UserService userService;
    private final CountryAnalysisGenerator countryAnalysisGenerator;
    private final ObjectMapper objectMapper;
    private final FirestoreService firestoreService;
    private final CommunityService communityService;
    private final FAOFoodPriceService faoFoodPriceService;
    private final QwenScoringService qwenScoringService;
    private final DailyBriefService dailyBriefService;
    private final IntelligenceSnapshotService intelligenceSnapshotService;
    private final MarketSignalService marketSignalService;
    private final GDACSService gdacsService;

    @org.springframework.beans.factory.annotation.Value("${ADMIN_API_KEY:notamy-admin-2026}")
    private String adminApiKey;

    /** Check admin API key for protected endpoints */
    private boolean isAdmin(String authHeader) {
        if (authHeader == null) return false;
        String key = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        return adminApiKey.equals(key);
    }

    /**
     * Health/keep-alive endpoint for Cloud Scheduler.
     * Prevents cold starts by pinging every 10 minutes.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "ok",
            "warmupComplete", cacheWarmupService.isAllReady(),
            "timestamp", LocalDateTime.now().toString()
        );
    }

    @GetMapping("/countries")
    public List<Country> getCountries() {
        return dashboardService.getEnrichedCountries();
    }

    @GetMapping("/countries/ranked")
    public List<Country> getRankedCountries() {
        return dashboardService.getCountriesRankedByCrisis();
    }

    /**
     * Aggregated country profile for country detail modal.
     * Single endpoint combining risk score, food security, climate, conflict,
     * economy, displacement, and recent reports.
     */
    @GetMapping("/countries/{iso3}/profile")
    public Map<String, Object> getCountryProfile(@PathVariable String iso3) {
        String upper = iso3.toUpperCase();
        CountryProfileData profile = countryProfileService.getProfile(upper);

        // Convert to map and add AI analysis from Firestore
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.convertValue(profile, Map.class);

        // >>> CHANGE POINT: check subscription tier before including AI analysis
        Map<String, Object> aiAnalysis = countryAnalysisGenerator.getPreGeneratedAnalysis(upper);
        if (aiAnalysis != null && aiAnalysis.get("analysis") != null) {
            result.put("aiAnalysis", aiAnalysis.get("analysis"));
            result.put("analysisGeneratedAt", aiAnalysis.get("generatedAt"));
        }

        return result;
    }

    /**
     * Structural indices and watchlist data for a country.
     * FSI (Fragile States Index), GPI (Global Peace Index),
     * plus IRC/ICG/IPC/GHO watchlist flags.
     */
    @GetMapping("/countries/{iso3}/indices")
    public Map<String, Object> getCountryIndices(@PathVariable String iso3) {
        String code = iso3.toUpperCase();
        Map<String, Object> result = new LinkedHashMap<>();
        StructuralIndexService.CountryIndices indices = structuralIndexService.getCountryIndices(code);
        if (indices != null) {
            result.put("fsi", Map.of("score", indices.getFsiScore(), "rank", indices.getFsiRank(),
                    "totalCountries", 179, "tier", indices.getFsiTier()));
            result.put("gpi", Map.of("score", indices.getGpiScore(), "rank", indices.getGpiRank(),
                    "totalCountries", 163, "tier", indices.getGpiTier()));
            result.put("watchlistCount", indices.getWatchlistCount());
        }
        result.put("watchlists", structuralIndexService.getCountryWatchlistEntries(code));
        return result;
    }

    /**
     * All structural indices for all countries (used by Countries page).
     */
    @GetMapping("/structural-indices")
    public Map<String, Object> getAllStructuralIndices() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indices", structuralIndexService.getAllCountryIndices());
        result.put("watchlists", structuralIndexService.getAllWatchlists());
        return result;
    }

    @GetMapping("/alerts")
    public Map<String, List<Alert>> getAlerts() {
        return dashboardService.getAlertsByCategory();
    }

    @GetMapping("/hazards")
    public List<Hazard> getHazards() {
        return dashboardService.getActiveHazards();
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return dashboardService.getDashboardStats();
    }

    // ========== NEW ENDPOINTS ==========

    @GetMapping("/climate")
    public List<ClimateData> getClimateData() {
        return climateService.getClimateAnomalies();
    }

    @GetMapping("/climate/stress")
    public List<ClimateData> getClimateStress() {
        return climateService.getCountriesWithClimateStress();
    }

    @GetMapping("/migration")
    public List<MigrationData> getMigrationData() {
        return migrationService.getRecentMigrationData();
    }

    @GetMapping("/migration/by-country")
    public Map<String, Long> getMigrationByCountry(
            @RequestParam(defaultValue = "12") int months) {
        return migrationService.getMigrationByCountry(months);
    }

    @GetMapping("/migration/monthly")
    public Map<String, Long> getMigrationMonthly(
            @RequestParam(defaultValue = "12") int months) {
        return migrationService.getMonthlyTotals(months);
    }

    @GetMapping("/foodsecurity")
    public List<FoodSecurityMetrics> getFoodSecurityMetrics() {
        return hungerMapService.getFoodSecurityMetrics();
    }

    // ========== WORLD BANK ECONOMIC DATA ==========

    @GetMapping("/economic/profiles")
    public List<CountryProfile> getEconomicProfiles() {
        return worldBankService.getEnrichedProfiles();
    }

    @GetMapping("/economic/countries")
    public List<CountryProfile> getCountryProfiles() {
        return worldBankService.getCountryProfiles();
    }

    @GetMapping("/economic/inflation")
    public List<EconomicIndicator> getInflationData() {
        return worldBankService.getInflationData();
    }

    @GetMapping("/economic/inflation/high")
    public List<EconomicIndicator> getHighInflationCountries() {
        return worldBankService.getHighInflationCountries();
    }

    @GetMapping("/economic/gdp")
    public List<EconomicIndicator> getGdpData() {
        return worldBankService.getGdpPerCapitaData();
    }

    @GetMapping("/economic/population")
    public List<EconomicIndicator> getPopulationData() {
        return worldBankService.getPopulationData();
    }

    @GetMapping("/economic/poverty")
    public List<EconomicIndicator> getPovertyData() {
        return worldBankService.getPovertyData();
    }

    // ========== UNHCR DISPLACEMENT DATA ==========

    @GetMapping("/displacement/stocks")
    public List<MobilityStock> getDisplacementStocks() {
        return unhcrService.getDisplacementByOrigin();
    }

    @GetMapping("/displacement/global")
    public MobilityStock getGlobalDisplacement() {
        return unhcrService.getGlobalSummary();
    }

    @GetMapping("/displacement/asylum")
    public List<MobilityFlow> getAsylumFlows() {
        return unhcrService.getAsylumApplications();
    }

    @GetMapping("/displacement/demographics")
    public Map<String, Object> getDisplacementDemographics() {
        return unhcrService.getDemographics();
    }

    @GetMapping("/displacement/solutions")
    public Map<String, Long> getDisplacementSolutions() {
        return unhcrService.getSolutions();
    }

    // ========== IOM DTM DATA ==========

    @GetMapping("/dtm/countries")
    public List<MobilityStock> getDtmCountryData() {
        return dtmService.getCountryLevelIdps();
    }

    @GetMapping("/dtm/reasons")
    public Map<String, Long> getDtmByReason() {
        return dtmService.getIdpsByReason();
    }

    @GetMapping("/dtm/operations")
    public List<Map<String, Object>> getDtmOperations() {
        return dtmService.getActiveOperations();
    }

    // ========== GDELT CONFLICT/MEDIA MONITORING ==========

    @GetMapping("/conflict/spikes")
    public DataResponse<List<MediaSpike>> getConflictSpikes() {
        // Read from warmup fallback only — never trigger GDELT from request threads.
        // GDELT requires 45s rate limiting × 42 countries = 30+ min computation;
        // calling it from a request thread causes 429 cascades and timeouts.
        List<MediaSpike> fallback = cacheWarmupService.getFallback("gdeltAllSpikes");
        if (fallback != null && !fallback.isEmpty()) {
            return DataResponse.ready(fallback);
        }

        return DataResponse.loading("Conflict data is being loaded in background...");
    }

    @GetMapping("/conflict/spike")
    public MediaSpike getConflictSpike(@RequestParam String iso3) {
        // Try warmup fallback first to avoid triggering GDELT from request thread
        List<MediaSpike> allSpikes = cacheWarmupService.getFallback("gdeltAllSpikes");
        if (allSpikes != null) {
            return allSpikes.stream()
                    .filter(s -> iso3.equalsIgnoreCase(s.getIso3()))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    @GetMapping("/conflict/events")
    public List<ConflictEvent> getConflictEvents(
            @RequestParam String iso3,
            @RequestParam(defaultValue = "7") int days) {
        return gdeltService.getConflictEvents(iso3, days);
    }

    @GetMapping("/conflict/tone")
    public Double getConflictTone(
            @RequestParam String iso3,
            @RequestParam(defaultValue = "7") int days) {
        return gdeltService.getAverageTone(iso3, days);
    }

    @GetMapping("/conflict/trend")
    public Map<String, Integer> getCrisisTrend(
            @RequestParam String iso3,
            @RequestParam(defaultValue = "30") int days) {
        return gdeltService.getCrisisVolumeTrend(iso3, days);
    }

    // ========== FEWS NET IPC FOOD SECURITY ==========

    @GetMapping("/ipc/alerts")
    public DataResponse<List<IPCAlert>> getIPCAlerts() {
        try {
            List<IPCAlert> data = fewsNetService.getAllIPCAlerts();
            if (data != null && !data.isEmpty()) {
                return DataResponse.ready(data);
            }
        } catch (Exception e) {
            // Try fallback
        }

        List<IPCAlert> fallback = cacheWarmupService.getFallback("fewsAllIPC");
        if (fallback != null && !fallback.isEmpty()) {
            return DataResponse.stale(fallback, null);
        }

        return DataResponse.loading("Food security data is being loaded...");
    }

    @GetMapping("/ipc/critical")
    public List<IPCAlert> getCriticalIPCAlerts() {
        return fewsNetService.getCriticalAlerts();
    }

    @GetMapping("/ipc/country")
    public IPCAlert getIPCByCountry(@RequestParam String iso2) {
        return fewsNetService.getLatestIPCPhase(iso2);
    }

    // ========== PREDICTIVE RISK SCORING ==========

    @GetMapping("/risk/scores")
    public DataResponse<List<RiskScore>> getAllRiskScores() {
        // Only serve from cache — never calculate on-demand (too expensive with GDELT rate limits)
        List<RiskScore> fallback = cacheWarmupService.getFallback("allRiskScores");
        if (fallback != null && !fallback.isEmpty()) {
            return DataResponse.ready(fallback);
        }

        // Warmup still in progress
        return DataResponse.loading("Risk scores are being calculated...");
    }

    @GetMapping("/risk/score")
    public RiskScore getRiskScore(@RequestParam String iso2) {
        // Try cached scores first
        List<RiskScore> scores = cacheWarmupService.getFallback("allRiskScores");
        if (scores != null) {
            return scores.stream()
                    .filter(s -> iso2.equals(s.getIso2()))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    @GetMapping("/risk/high")
    public List<RiskScore> getHighRiskCountries() {
        List<RiskScore> scores = cacheWarmupService.getFallback("allRiskScores");
        if (scores == null) return List.of();
        return scores.stream().filter(RiskScore::isHighRisk).toList();
    }

    @GetMapping("/risk/confirmed")
    public List<RiskScore> getConfirmedWarnings() {
        List<RiskScore> scores = cacheWarmupService.getFallback("allRiskScores");
        if (scores == null) return List.of();
        return scores.stream()
                .filter(RiskScore::isConfirmed)
                .filter(s -> s.getScore() >= 51)
                .toList();
    }

    @GetMapping("/risk/summary")
    public Map<String, Object> getRiskSummary() {
        List<RiskScore> scores = cacheWarmupService.getFallback("allRiskScores");
        if (scores == null || scores.isEmpty()) return Map.of("total", 0);
        // Build summary from cached scores directly
        var byLevel = scores.stream()
                .collect(java.util.stream.Collectors.groupingBy(RiskScore::getRiskLevel, java.util.stream.Collectors.counting()));
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

    // ========== CLIMATE (OPEN-METEO) ==========

    @GetMapping("/precipitation/anomalies")
    public DataResponse<List<PrecipitationAnomaly>> getPrecipitationAnomalies() {
        try {
            List<PrecipitationAnomaly> data = openMeteoService.getAllPrecipitationAnomalies();
            if (data != null && !data.isEmpty()) {
                return DataResponse.ready(data);
            }
        } catch (Exception e) {
            // Try fallback
        }

        List<PrecipitationAnomaly> fallback = cacheWarmupService.getFallback("allPrecipAnomalies");
        if (fallback != null && !fallback.isEmpty()) {
            return DataResponse.stale(fallback, null);
        }

        return DataResponse.loading("Climate data is being loaded...");
    }

    @GetMapping("/precipitation/anomaly")
    public PrecipitationAnomaly getPrecipitationAnomaly(@RequestParam String iso2) {
        return openMeteoService.getPrecipitationAnomaly(iso2);
    }

    @GetMapping("/precipitation/droughts")
    public List<PrecipitationAnomaly> getDroughtCountries() {
        return openMeteoService.getDroughtCountries();
    }

    // ========== CURRENCY (EXCHANGE RATES) ==========

    @GetMapping("/currency/all")
    public DataResponse<List<CurrencyData>> getAllCurrencyData() {
        try {
            List<CurrencyData> data = currencyService.getAllCurrencyData();
            if (data != null && !data.isEmpty()) {
                return DataResponse.ready(data);
            }
        } catch (Exception e) {
            // Try fallback
        }

        List<CurrencyData> fallback = cacheWarmupService.getFallback("allCurrencyData");
        if (fallback != null && !fallback.isEmpty()) {
            return DataResponse.stale(fallback, null);
        }

        return DataResponse.loading("Currency data is being loaded...");
    }

    @GetMapping("/currency/data")
    public CurrencyData getCurrencyData(@RequestParam String iso2) {
        return currencyService.getCurrencyData(iso2);
    }

    @GetMapping("/currency/devaluing")
    public List<CurrencyData> getDevaluingCurrencies() {
        return currencyService.getDevaluingCurrencies();
    }

    @GetMapping("/currency/crisis")
    public List<CurrencyData> getCurrencyCrisis() {
        return currencyService.getCurrencyCrisis();
    }

    // ========== AI ANALYSIS ==========

    @GetMapping("/analysis/global")
    public AIAnalysis getGlobalAnalysis() {
        String dataVersion = analysisService.generateDataVersion();
        return analysisService.analyzeGlobal(dataVersion);
    }

    @GetMapping("/analysis/country")
    public AIAnalysis getCountryAnalysis(@RequestParam String iso3) {
        String dataVersion = analysisService.generateDataVersion();
        return analysisService.analyzeCountry(iso3.toUpperCase(), dataVersion);
    }

    @GetMapping("/analysis/region")
    public AIAnalysis getRegionalAnalysis(@RequestParam String region) {
        String dataVersion = analysisService.generateDataVersion();
        return analysisService.analyzeRegion(region.toLowerCase(), dataVersion);
    }

    @GetMapping("/analysis/version")
    public Map<String, String> getDataVersion() {
        return Map.of("version", analysisService.generateDataVersion());
    }

    /**
     * Deep contextual analysis.
     * Single call triggered by button for cost control.
     * Provides dynamic weighting based on current context.
     */
    @GetMapping("/analysis/deep")
    public AnalysisService.DeepAnalysisResult getDeepAnalysis(
            @RequestParam(required = false) String iso3) {
        // If no ISO3 provided, analyze top risk country
        String targetIso3 = iso3 != null && !iso3.isBlank()
                ? iso3.toUpperCase(java.util.Locale.ROOT)
                : analysisService.getTopRiskCountryIso3();
        return analysisService.deepAnalyze(targetIso3);
    }

    // ========== DATA FRESHNESS ==========

    @GetMapping("/data/freshness")
    public DataSourceStatus.DataFreshnessSummary getDataFreshness() {
        return dataFreshnessService.getDataFreshness();
    }

    // ========== REGIONAL CLUSTER ALERTS ==========

    @GetMapping("/clusters")
    public List<RegionalClusterService.ClusterAlert> getRegionalClusters() {
        // Use cached scores to avoid triggering expensive on-demand calculation
        List<RiskScore> scores = cacheWarmupService.getFallback("allRiskScores");
        if (scores == null || scores.isEmpty()) {
            return List.of();
        }
        return regionalClusterService.analyzeRegionalClusters(scores);
    }

    // ========== RELIEFWEB HUMANITARIAN REPORTS ==========

    @GetMapping("/reliefweb/reports")
    public List<ReliefWebService.HumanitarianReport> getReliefWebReports(
            @RequestParam String iso3,
            @RequestParam(defaultValue = "5") int limit) {
        return reliefWebService.getLatestReports(iso3.toUpperCase(), limit);
    }

    @GetMapping("/reliefweb/disasters")
    public List<ReliefWebService.DisasterInfo> getReliefWebDisasters(
            @RequestParam String iso3) {
        return reliefWebService.getActiveDisasters(iso3.toUpperCase());
    }

    // ========== CACHE MANAGEMENT ==========

    /**
     * Get cache status for all main data tabs
     * Frontend can poll this to know when data is ready
     */
    @GetMapping("/cache/status")
    public Map<String, Object> getCacheStatus() {
        return cacheWarmupService.getWarmupStatus();
    }

    @PostMapping("/cache/clear")
    public Object clearCache(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return Map.of("error", "Unauthorized");
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
        return Map.of("status", "cleared", "caches", String.join(", ", cacheManager.getCacheNames()));
    }

    // ========== INTELLIGENCE PREPARATION ==========

    /**
     * Preview prepared intelligence for a country (debug/verification).
     */
    @GetMapping("/intelligence/prepared/{iso3}")
    public Map<String, Object> getPreviewPreparedIntel(@PathVariable String iso3) {
        IntelligencePrepService.PreparedIntelligence intel =
                intelligencePrepService.getIntelligence(iso3.toUpperCase());
        Map<String, Object> result = new LinkedHashMap<>();
        if (intel == null) {
            result.put("status", "not_prepared");
            return result;
        }
        result.put("iso3", intel.iso3);
        result.put("preparedAt", intel.preparedAt);
        result.put("articleCount", intel.articleCount);
        result.put("newsArticles", intel.newsArticles);
        result.put("reliefWebReports", intel.reliefWebReports);
        result.put("dataPackPreview", intel.toDataPackSection());
        return result;
    }

    /**
     * Trigger intelligence preparation for all countries.
     * Pre-fetches news articles with content snippets, stores in Redis.
     * Takes ~5-8 minutes to complete. Can be called by Cloud Scheduler.
     */
    @PostMapping("/intelligence/prepare")
    public Object prepareIntelligence(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return Map.of("error", "Unauthorized");
        intelligencePrepService.prepareAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "started");
        result.put("message", "Intelligence preparation started for all countries. Check /api/intelligence/prepare/status for progress.");
        return result;
    }

    /**
     * Check intelligence preparation status.
     */
    @GetMapping("/intelligence/prepare/status")
    public Map<String, Object> getIntelligencePrepStatus() {
        return intelligencePrepService.getStatus();
    }

    // ========== INTELLIGENCE FEED (CENTRALIZED) ==========

    /**
     * Get complete Intelligence Feed from cache ONLY
     * Never waits for live data - returns WARMING_UP if not ready
     * The cron job keeps this cache fresh
     */
    @GetMapping("/intelligence/feed")
    public IntelligenceFeedService.IntelligenceFeedData getIntelligenceFeed() {
        // First try in-memory fallback (fastest, survives Redis issues)
        IntelligenceFeedService.IntelligenceFeedData cached =
            cacheWarmupService.getFallback("intelligenceFeed");

        if (cached != null) {
            return cached;
        }

        // Return warming up status - never block waiting for live data
        IntelligenceFeedService.IntelligenceFeedData warmingUp =
            new IntelligenceFeedService.IntelligenceFeedData();
        warmingUp.setStatus("WARMING_UP");
        warmingUp.setTimestamp(LocalDateTime.now().toString());
        return warmingUp;
    }

    // ========== TOPIC-BASED INTELLIGENCE ==========

    /**
     * Search intelligence by topic and region
     * Example: /api/intelligence/search?topic=migration&region=central-america
     */
    @GetMapping("/intelligence/search")
    public TopicIntelligenceService.TopicIntelligenceResult searchIntelligence(
            @RequestParam String topic,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String country) {
        return topicIntelligenceService.searchByTopic(topic, region, country);
    }

    /**
     * Get available topics for the filter UI
     */
    @GetMapping("/intelligence/topics")
    public List<Map<String, String>> getAvailableTopics() {
        return topicIntelligenceService.getAvailableTopics();
    }

    /**
     * Get available regions for the filter UI
     */
    @GetMapping("/intelligence/regions")
    public List<Map<String, String>> getAvailableRegions() {
        return topicIntelligenceService.getAvailableRegions();
    }

    // ========== DAILY INTELLIGENCE BRIEFING ==========

    /**
     * Get daily intelligence briefing organized by region
     * This is the main endpoint for the Intelligence Briefing panel
     */
    @GetMapping("/briefing/daily")
    public NewsAggregatorService.DailyBriefing getDailyBriefing() {
        // Try in-memory fallback first
        NewsAggregatorService.DailyBriefing cached =
            cacheWarmupService.getFallback("dailyBriefing");

        if (cached != null) {
            return cached;
        }

        // Return warming up status - let cron fill the cache
        NewsAggregatorService.DailyBriefing warmingUp =
            new NewsAggregatorService.DailyBriefing();
        warmingUp.setStatus("WARMING_UP");
        warmingUp.setDate(java.time.LocalDate.now().toString());
        warmingUp.setTimestamp(LocalDateTime.now().toString());
        return warmingUp;
    }

    /**
     * Get available regions for briefing filter
     */
    @GetMapping("/briefing/regions")
    public List<Map<String, String>> getBriefingRegions() {
        return List.of(
            Map.of("code", "LAC", "name", "Latin America & Caribbean"),
            Map.of("code", "MENA", "name", "Middle East & North Africa"),
            Map.of("code", "AFRICA_EAST", "name", "East Africa & Horn"),
            Map.of("code", "AFRICA_WEST", "name", "West & Central Africa"),
            Map.of("code", "ASIA", "name", "Asia & Pacific"),
            Map.of("code", "EUROPE", "name", "Europe & Central Asia")
        );
    }

    // ========== SITUATION DETECTION ENGINE ==========

    /**
     * AI Situation Detection.
     * Triggered by button click for cost control.
     * Uses AI to semantically analyze triggered countries.
     * Result is cached for subsequent page loads.
     */
    @GetMapping("/situations/detect")
    public AnalysisService.SituationDetectionResult detectSituations() {
        return analysisService.detectSituations();
    }

    /**
     * Get cached AI situation detection results.
     * Returns cached result if available, otherwise null.
     */
    @GetMapping("/situations/cached")
    public AnalysisService.SituationDetectionResult getCachedSituations() {
        AnalysisService.SituationDetectionResult cached = analysisService.getCachedSituationResult();
        if (cached != null) {
            return cached;
        }
        // Return empty result with message
        return AnalysisService.SituationDetectionResult.builder()
                .status("NO_CACHE")
                .message("Click 'Detect Situations' to analyze")
                .situations(java.util.List.of())
                .build();
    }

    /**
     * Get active humanitarian situations (keyword-based, cached)
     * This is the main Intelligence endpoint - shows "What's getting worse? Where? Why?"
     */
    @GetMapping("/situations/active")
    public SituationDetectionService.SituationReport getActiveSituations() {
        // Try in-memory fallback first
        SituationDetectionService.SituationReport cached =
            cacheWarmupService.getFallback("activeSituations");

        if (cached != null) {
            return cached;
        }

        // Return warming up status with consistent structure
        SituationDetectionService.SituationReport warmingUp =
            new SituationDetectionService.SituationReport();
        warmingUp.setStatus("WARMING_UP");
        warmingUp.setDate(java.time.LocalDate.now().toString());
        warmingUp.setTimestamp(LocalDateTime.now().toString());
        warmingUp.setTodaySummary(List.of("Detecting active situations..."));
        warmingUp.setSituations(List.of()); // Always return empty array, never null
        warmingUp.setTotalSituations(0);
        return warmingUp;
    }

    // ========== TOPIC REPORT GENERATOR ==========

    /**
     * Generate topic-based intelligence report
     * Combines GDELT media coverage with ReliefWeb operational reports
     * Example: /api/intelligence/topic-report?topic=migration&region=lac&days=7
     */
    /**
     * Get intelligence report — reads from pre-generated Firestore cache.
     * Falls back to live generation if no pre-generated report exists.
     *
     * >>> CHANGE POINT: add auth check here when authentication is implemented
     * >>> CHANGE POINT: add credit check here when paywall is implemented
     * >>> For paywall: return keyDevelopments free, narrative only with credits
     */
    @GetMapping("/intelligence/topic-report")
    public Object getTopicReport(
            @RequestParam String topic,
            @RequestParam(required = false) String region,
            @RequestParam(defaultValue = "7") int days) {

        // Country-specific report (region starts with "country:")
        if (region != null && region.startsWith("country:")) {
            String iso3 = region.substring(8).toUpperCase();
            // Try pre-generated country report (skip if stale)
            Map<String, Object> preGenerated = reportSchedulerService.getPreGeneratedReport(topic, "country_" + iso3);
            if (preGenerated != null) {
                String narrative = (String) preGenerated.get("narrative");
                if (narrative != null && !narrative.isBlank() && narrative.length() > 50) {
                    preGenerated.put("source", "pre-generated");
                    return preGenerated;
                }
            }
            return topicReportService.generateReport(topic, iso3.toLowerCase(), 7);
        }

        // Try pre-generated regional report first (instant, no API cost)
        // Skip if report is stale (no narrative) — regenerate instead
        if (region != null && !region.isEmpty()) {
            Map<String, Object> preGenerated = reportSchedulerService.getPreGeneratedReport(topic, region);
            if (preGenerated != null) {
                String narrative = (String) preGenerated.get("narrative");
                boolean hasNarrative = narrative != null && !narrative.isBlank() && narrative.length() > 50;
                if (hasNarrative) {
                    preGenerated.put("source", "pre-generated");
                    return preGenerated;
                }
                // Stale report (no narrative) — fall through to live generation
            }
        }

        // Generate live
        return topicReportService.generateReport(topic, region, 7);
    }

    /**
     * Trigger manual report generation (admin only).
     *
     * >>> CHANGE POINT: protect with admin auth when auth is implemented
     */
    @PostMapping("/intelligence/generate-all-reports")
    public Object triggerReportGeneration(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return Map.of("error", "Unauthorized");
        return reportSchedulerService.triggerGeneration();
    }

    // ==========================================
    // FAO FOOD PRICE INDEX
    // ==========================================

    @GetMapping("/fao/food-price-index")
    public Map<String, Object> getFAOFoodPriceIndex() {
        var latest = faoFoodPriceService.getLatest();
        var recent = faoFoodPriceService.getRecent(24);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("latest", latest);
        result.put("trend24m", recent);
        result.put("source", "FAO Food Price Index (FFPI) — base 2014-2016=100");
        return result;
    }

    // ==========================================
    // TRANSLATE HEADLINES
    // ==========================================

    @PostMapping("/translate-headlines")
    public Object translateHeadlines(@RequestBody Map<String, Object> body) {
        String lang = (String) body.getOrDefault("lang", "en");
        if ("en".equals(lang)) return body; // No translation needed
        @SuppressWarnings("unchecked")
        List<String> headlines = (List<String>) body.get("headlines");
        if (headlines == null || headlines.isEmpty()) return Map.of("headlines", List.of());
        // Limit to 10 headlines max
        if (headlines.size() > 10) headlines = headlines.subList(0, 10);
        var translated = dailyBriefService.translateHeadlines(headlines, lang);
        return Map.of("headlines", translated != null ? translated : headlines);
    }

    // ==========================================
    // GDACS DISASTER ALERTS
    // ==========================================

    @GetMapping("/disasters/alerts")
    public Object getDisasterAlerts() {
        var alerts = gdacsService.getCurrentAlerts();
        return Map.of("alerts", alerts, "count", alerts.size());
    }

    // ==========================================
    // MARKET SIGNALS (experimental)
    // ==========================================

    @GetMapping("/market-signals")
    public Object getMarketSignals() {
        var signals = marketSignalService.getMarketSignals();
        if (signals != null) return signals;
        return Map.of("status", "generating", "message", "Market signals are being calculated...");
    }

    // ==========================================
    // PREDICTIVE ANALYSIS
    // ==========================================

    @GetMapping("/intelligence/predictive")
    public Object getPredictiveAnalysis(@RequestParam(defaultValue = "en") String lang) {
        var analysis = intelligenceSnapshotService.getTodayAnalysis(lang);
        if (analysis != null) return analysis;
        return Map.of("status", "generating", "message", "Predictive analysis is being generated. This may take up to 60 seconds on first request.");
    }

    @PostMapping("/intelligence/predictive/regenerate")
    public Object regeneratePredictiveAnalysis(@RequestParam(defaultValue = "en") String lang,
                                               @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return Map.of("error", "Unauthorized");
        var analysis = intelligenceSnapshotService.regenerate(lang);
        if (analysis != null) return analysis;
        return Map.of("error", "Failed to regenerate");
    }

    // ==========================================
    // NOWCAST BRIEF
    // ==========================================

    @GetMapping("/nowcast/brief")
    public Object getNowcastBrief(@RequestParam(defaultValue = "en") String lang) {
        var brief = dailyBriefService.getNowcastBrief(lang);
        if (brief != null) return brief;
        return Map.of("status", "none", "message", "Nowcast brief not yet generated");
    }

    // ==========================================
    // DAILY BRIEF
    // ==========================================

    @GetMapping("/editorial-columns")
    public Object getEditorialColumns(@RequestParam(defaultValue = "en") String lang) {
        var cols = dailyBriefService.getEditorialColumns(lang);
        if (cols != null) return cols;
        return Map.of("status", "none");
    }

    @GetMapping("/daily-brief")
    public Object getDailyBrief(@RequestParam(defaultValue = "en") String lang) {
        var brief = dailyBriefService.getTodayBrief(lang);
        if (brief != null) return brief;
        return Map.of("status", "none", "message", "Today's brief not yet generated");
    }

    @PostMapping("/daily-brief/generate")
    public Object generateDailyBrief(@RequestParam(defaultValue = "en") String lang,
                                     @RequestParam(defaultValue = "false") boolean force,
                                     @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return Map.of("error", "Unauthorized");
        var brief = dailyBriefService.generateAndSave(lang, force);
        // Also generate nowcast brief (piggyback on daily trigger)
        try { dailyBriefService.getNowcastBrief(); } catch (Exception e) { /* non-blocking */ }
        if (brief != null) return brief;
        return Map.of("error", "Failed to generate brief");
    }

    @GetMapping("/country-brief/{iso3}")
    public Object getCountryBrief(@PathVariable String iso3,
                                   @RequestParam(defaultValue = "en") String lang) {
        var brief = dailyBriefService.getCountryBrief(iso3, lang);
        if (brief != null) return brief;
        return Map.of("error", "Failed to generate country brief");
    }

    @PostMapping("/daily-brief/deep-dive")
    public Object getDeepDive(@RequestBody Map<String, String> body,
                              @RequestParam(defaultValue = "en") String lang) {
        String country = body.get("country");
        String situation = body.get("situation");
        if (country == null || country.isBlank()) return Map.of("error", "Country required");
        if (situation == null || situation.isBlank()) return Map.of("error", "Situation required");
        // Limit input length to prevent abuse
        if (country.length() > 100) country = country.substring(0, 100);
        if (situation.length() > 200) situation = situation.substring(0, 200);
        var dive = dailyBriefService.getOrGenerateDeepDive(country, situation, lang);
        if (dive != null) return dive;
        return Map.of("error", "Failed to generate deep dive");
    }

    // ==========================================
    // COMMUNITY — COMMENTS & REACTIONS
    // ==========================================

    @PostMapping("/community/reports/{reportId}/comments")
    public Object addComment(@PathVariable String reportId,
                             @RequestBody Map<String, String> body,
                             @RequestHeader(value = "Authorization", required = false) String authHeader) {
        var token = verifyAuth(authHeader);
        if (token == null) return Map.of("error", "Authentication required");

        String text = body.get("text");
        if (text == null || text.isBlank()) return Map.of("error", "Text required");

        var comment = communityService.addComment(reportId, token.getUid(), token.getName(), token.getPicture(), text);
        if (comment != null) return Map.of("success", true, "comment", comment);
        return Map.of("error", "Failed to add comment");
    }

    @GetMapping("/community/reports/{reportId}/comments")
    public Object getComments(@PathVariable String reportId,
                              @RequestParam(defaultValue = "20") int limit,
                              @RequestParam(required = false) String cursor) {
        var page = communityService.getComments(reportId, limit, cursor);
        if (page != null) return page;
        return Map.of("comments", List.of(), "hasMore", false);
    }

    @DeleteMapping("/community/reports/{reportId}/comments/{commentId}")
    public Object deleteComment(@PathVariable String reportId,
                                @PathVariable String commentId,
                                @RequestHeader(value = "Authorization", required = false) String authHeader) {
        var token = verifyAuth(authHeader);
        if (token == null) return Map.of("error", "Authentication required");

        boolean deleted = communityService.deleteComment(commentId, token.getUid());
        return Map.of("success", deleted);
    }

    @PostMapping("/community/reports/{reportId}/react")
    public Object toggleReaction(@PathVariable String reportId,
                                 @RequestBody Map<String, String> body,
                                 @RequestHeader(value = "Authorization", required = false) String authHeader) {
        var token = verifyAuth(authHeader);
        if (token == null) return Map.of("error", "Authentication required");

        String type = body.get("reactionType");
        var counts = communityService.toggleReaction(reportId, token.getUid(), type);
        if (counts != null) return counts;
        return Map.of("error", "Failed to toggle reaction");
    }

    @GetMapping("/community/reports/{reportId}/reactions")
    public Object getReactions(@PathVariable String reportId,
                               @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String userId = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            var token = verifyAuth(authHeader);
            if (token != null) userId = token.getUid();
        }
        var counts = communityService.getReactionCounts(reportId, userId);
        if (counts != null) return counts;
        return Map.of("useful", 0, "insightful", 0, "verify", 0, "comments", 0);
    }

    private com.google.firebase.auth.FirebaseToken verifyAuth(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return userService.verifyToken(authHeader.substring(7));
    }

    // ==========================================
    // QWEN AI SCORING
    // ==========================================

    @GetMapping("/scores/qwen/{iso3}")
    public Object getQwenScore(@PathVariable String iso3) {
        var score = qwenScoringService.getScore(iso3.toUpperCase());
        if (score != null) return score;
        return Map.of("error", "No Qwen score for " + iso3, "status", "NOT_FOUND");
    }

    @PostMapping("/scores/qwen/generate/{iso3}")
    public Object generateQwenScore(@PathVariable String iso3,
                                     @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return Map.of("error", "Unauthorized");
        var score = qwenScoringService.scoreCountry(iso3.toUpperCase());
        if (score != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.convertValue(score, Map.class);
            data.put("timestamp", System.currentTimeMillis());
            firestoreService.saveDocument("qwenScores", iso3.toUpperCase(), data);
            return score;
        }
        return Map.of("error", "Scoring failed for " + iso3);
    }

    @PostMapping("/scores/qwen/generate-all")
    public Object generateAllQwenScores(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return Map.of("error", "Unauthorized");
        new Thread(() -> qwenScoringService.scoreAllCountries(), "qwen-scorer").start();
        return Map.of("status", "STARTED", "countries", MonitoredCountries.CRISIS_COUNTRIES.size());
    }

    // ==========================================
    // USER & AUTH ENDPOINTS
    // ==========================================

    /**
     * Login/register user. Called from frontend after Google Sign-In.
     * Creates user profile in Firestore on first login.
     *
     * >>> CHANGE POINT: return subscription status for frontend paywall logic
     */
    @PostMapping("/auth/login")
    public Map<String, Object> loginUser(@RequestBody Map<String, String> body) {
        String idToken = body.get("idToken");
        if (idToken == null || idToken.isBlank()) {
            return Map.of("error", "Missing idToken");
        }

        var token = userService.verifyToken(idToken);
        if (token == null) {
            return Map.of("error", "Invalid token");
        }

        var user = userService.getOrCreateUser(
            token.getUid(),
            token.getEmail(),
            token.getName(),
            token.getPicture()
        );

        return Map.of(
            "status", "OK",
            "uid", token.getUid(),
            "email", token.getEmail() != null ? token.getEmail() : "",
            "tier", user.getOrDefault("tier", "free"),
            "user", user
        );
    }

    /**
     * Get user profile and subscription status.
     *
     * >>> CHANGE POINT: add subscription details (expiry, plan name, etc.)
     */
    @GetMapping("/auth/profile")
    public Map<String, Object> getUserProfile(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Map.of("error", "Not authenticated", "tier", "free");
        }

        String idToken = authHeader.substring(7);
        var token = userService.verifyToken(idToken);
        if (token == null) {
            return Map.of("error", "Invalid token", "tier", "free");
        }

        String tier = userService.getUserTier(token.getUid());
        return Map.of(
            "status", "OK",
            "uid", token.getUid(),
            "tier", tier,
            "isPro", userService.isProUser(token.getUid())
        );
    }

    // ========== Q&A WITH CITATIONS ==========

    /**
     * AI-powered Q&A using cached news sources.
     * Returns answer with inline [N] citations referencing source articles.
     */
    @GetMapping("/intelligence/ask")
    public AnalysisService.QAResponse askQuestion(@RequestParam String q) {
        if (q == null || q.isBlank() || q.length() > 500) {
            return AnalysisService.QAResponse.builder()
                    .question(q)
                    .answer("Please provide a question (max 500 characters).")
                    .sources(java.util.List.of())
                    .generatedAt(java.time.LocalDateTime.now())
                    .build();
        }
        return analysisService.answerQuestion(q.trim());
    }

    // ========== NEWS FEED (TWO-COLUMN) ==========

    /**
     * Two-column news feed: ReliefWeb (humanitarian) | Media (GDELT + RSS)
     * This is the primary endpoint for the News Feed section.
     */
    @GetMapping("/news-feed")
    public StoryService.NewsFeedData getNewsFeed(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String topic) {
        return storyService.getNewsFeed(region, topic);
    }

    // ========== NEWS FEED (STORIES - LEGACY) ==========

    /**
     * Get today's stories - deduped and clustered headlines
     * Used by Overview section. Kept for backward compatibility.
     */
    @GetMapping("/stories")
    public List<Story> getStories(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String topic,
            @RequestParam(defaultValue = "1") int days) {
        return storyService.getStories(region, topic, days);
    }

    /**
     * Get top stories for overview (limit 5)
     */
    @GetMapping("/stories/top")
    public List<Story> getTopStories(@RequestParam(defaultValue = "5") int limit) {
        return storyService.getTopStories(limit);
    }

    /**
     * Get stories grouped by region
     */
    @GetMapping("/stories/by-region")
    public Map<String, List<Story>> getStoriesByRegion() {
        return storyService.getStoriesByRegion();
    }

    // ========== REGIONAL PULSE (OVERVIEW) ==========

    /**
     * Get Regional Pulse data for Overview
     * Shows top hotspots per region with critical/high counts
     */
    @GetMapping("/regions/pulse")
    public RegionService.RegionalPulseData getRegionalPulse() {
        return regionService.getRegionalPulse();
    }

    /**
     * Get full regional detail: all countries ranked with driver mix.
     * Used for regional drill-down view.
     */
    @GetMapping("/regions/{code}/detail")
    public RegionService.RegionDetailData getRegionDetail(@PathVariable String code) {
        return regionService.getRegionDetail(code.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Get context headlines for a specific region
     */
    @GetMapping("/regions/context")
    public List<RegionService.ContextItem> getRegionContext(@RequestParam String region) {
        return regionService.getRegionContext(region.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Get all context headlines for Overview (one per region)
     */
    @GetMapping("/regions/context/all")
    public List<RegionService.ContextItem> getOverviewContext() {
        return regionService.getOverviewContext();
    }

    // ========== WHO Disease Outbreak News ==========

    @GetMapping("/who/outbreaks")
    public List<WHODiseaseOutbreakService.DiseaseOutbreak> getWHOOutbreaks() {
        return whoDiseaseOutbreakService.getRecentOutbreaks();
    }

    @GetMapping("/who/outbreaks/{iso3}")
    public List<WHODiseaseOutbreakService.DiseaseOutbreak> getWHOOutbreaksForCountry(@PathVariable String iso3) {
        return whoDiseaseOutbreakService.getOutbreaksForCountry(iso3.toUpperCase());
    }

    // ==========================================
    // FOOD INSECURITY NOWCASTING
    // ==========================================

    @GetMapping("/nowcast/food-insecurity")
    public Map<String, Object> getNowcast() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", nowcastService.isReady() ? "READY" : "MODEL_NOT_LOADED");
        response.put("modelType", "4-model ensemble: LightGBM(MAE) + LightGBM(Huber) + XGBoost + LightGBM(Quantile)");
        response.put("target", "90-day % change in food insecurity proxy");
        response.put("proxy", "avg(FCG<=2%, rCSI>=19%) from WFP HungerMap LIVE");
        response.put("accuracy", Map.of(
            "testMAE", 1.20,
            "testR2", 0.9828,
            "directionAccuracy", "98.6%"
        ));
        response.put("historyCountries", nowcastService.getHistoryCountries());
        response.put("predictions", nowcastService.getNowcastAll());
        response.put("timestamp", java.time.Instant.now().toString());
        response.put("note", "Predictions improve as history accumulates. Initial predictions use current values as proxy for historical lags (LOW confidence).");
        return response;
    }

    @GetMapping("/nowcast/food-insecurity/{iso3}")
    public Map<String, Object> getNowcastCountry(@PathVariable String iso3) {
        String upper = iso3.toUpperCase();
        List<NowcastResult> all = nowcastService.getNowcastAll();
        NowcastResult country = all.stream()
            .filter(r -> upper.equals(r.getIso3()))
            .findFirst().orElse(null);

        Map<String, Object> response = new LinkedHashMap<>();
        if (country != null) {
            response.put("status", "OK");
            response.put("prediction", country);
            response.put("historyDays", nowcastService.getHistoryDays(upper));
        } else {
            response.put("status", "NOT_FOUND");
            response.put("message", "No nowcast data for " + upper);
        }
        return response;
    }
}
