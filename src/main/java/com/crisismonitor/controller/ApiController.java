package com.crisismonitor.controller;

import com.crisismonitor.model.*;
import com.crisismonitor.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final ClaudeAnalysisService claudeAnalysisService;
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
    public CountryProfileData getCountryProfile(@PathVariable String iso3) {
        return countryProfileService.getProfile(iso3.toUpperCase());
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
        try {
            List<MediaSpike> data = gdeltService.getAllConflictSpikes();
            if (data != null && !data.isEmpty()) {
                return DataResponse.ready(data);
            }
        } catch (Exception e) {
            // Try fallback
        }

        List<MediaSpike> fallback = cacheWarmupService.getFallback("gdeltAllSpikes");
        if (fallback != null && !fallback.isEmpty()) {
            return DataResponse.stale(fallback, null);
        }

        return DataResponse.loading("Conflict data is being loaded...");
    }

    @GetMapping("/conflict/spike")
    public MediaSpike getConflictSpike(@RequestParam String iso3) {
        return gdeltService.getConflictSpikeIndex(iso3);
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

    // ========== AI ANALYSIS (CLAUDE) ==========

    @GetMapping("/analysis/global")
    public AIAnalysis getGlobalAnalysis() {
        String dataVersion = claudeAnalysisService.generateDataVersion();
        return claudeAnalysisService.analyzeGlobal(dataVersion);
    }

    @GetMapping("/analysis/country")
    public AIAnalysis getCountryAnalysis(@RequestParam String iso3) {
        String dataVersion = claudeAnalysisService.generateDataVersion();
        return claudeAnalysisService.analyzeCountry(iso3.toUpperCase(), dataVersion);
    }

    @GetMapping("/analysis/region")
    public AIAnalysis getRegionalAnalysis(@RequestParam String region) {
        String dataVersion = claudeAnalysisService.generateDataVersion();
        return claudeAnalysisService.analyzeRegion(region.toLowerCase(), dataVersion);
    }

    @GetMapping("/analysis/version")
    public Map<String, String> getDataVersion() {
        return Map.of("version", claudeAnalysisService.generateDataVersion());
    }

    /**
     * Deep contextual analysis with Claude.
     * Single call triggered by button for cost control.
     * Provides dynamic weighting based on current context.
     */
    @GetMapping("/analysis/deep")
    public ClaudeAnalysisService.DeepAnalysisResult getDeepAnalysis(
            @RequestParam(required = false) String iso3) {
        // If no ISO3 provided, analyze top risk country
        String targetIso3 = iso3 != null && !iso3.isBlank()
                ? iso3.toUpperCase(java.util.Locale.ROOT)
                : claudeAnalysisService.getTopRiskCountryIso3();
        return claudeAnalysisService.deepAnalyze(targetIso3);
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
    public Map<String, String> clearCache() {
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
        return Map.of("status", "cleared", "caches", String.join(", ", cacheManager.getCacheNames()));
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
     * Claude-Native Situation Detection.
     * Triggered by button click for cost control.
     * Uses Claude to semantically analyze triggered countries.
     * Result is cached for subsequent page loads.
     */
    @GetMapping("/situations/detect")
    public ClaudeAnalysisService.SituationDetectionResult detectSituations() {
        return claudeAnalysisService.detectSituations();
    }

    /**
     * Get cached Claude situation detection results.
     * Returns cached result if available, otherwise null.
     */
    @GetMapping("/situations/claude-cached")
    public ClaudeAnalysisService.SituationDetectionResult getCachedClaudeSituations() {
        ClaudeAnalysisService.SituationDetectionResult cached = claudeAnalysisService.getCachedSituationResult();
        if (cached != null) {
            return cached;
        }
        // Return empty result with message
        return ClaudeAnalysisService.SituationDetectionResult.builder()
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
    @GetMapping("/intelligence/topic-report")
    public TopicReportService.IntelligenceReport getTopicReport(
            @RequestParam String topic,
            @RequestParam(required = false) String region,
            @RequestParam(defaultValue = "7") int days) {
        return topicReportService.generateReport(topic, region, days);
    }

    // ========== Q&A WITH CITATIONS ==========

    /**
     * AI-powered Q&A using cached news sources.
     * Returns answer with inline [N] citations referencing source articles.
     */
    @GetMapping("/intelligence/ask")
    public ClaudeAnalysisService.QAResponse askQuestion(@RequestParam String q) {
        if (q == null || q.isBlank() || q.length() > 500) {
            return ClaudeAnalysisService.QAResponse.builder()
                    .question(q)
                    .answer("Please provide a question (max 500 characters).")
                    .sources(java.util.List.of())
                    .generatedAt(java.time.LocalDateTime.now())
                    .build();
        }
        return claudeAnalysisService.answerQuestion(q.trim());
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
}
