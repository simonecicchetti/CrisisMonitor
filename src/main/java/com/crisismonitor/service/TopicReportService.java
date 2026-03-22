package com.crisismonitor.service;

import com.crisismonitor.model.MediaSpike;
import com.crisismonitor.model.MobilityStock;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Intelligence-Grade Topic Report Generator
 *
 * Generates decision-ready reports by combining:
 * - Layer 1: Media signals (GDELT)
 * - Layer 2: Operational data (ReliefWeb, DTM, UNHCR)
 * - Layer 3: AI synthesis (Qwen)
 * - Layer 4: Trend signals with confidence scores
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopicReportService {

    private final GDELTService gdeltService;
    private final ReliefWebService reliefWebService;
    private final DTMService dtmService;
    private final UNHCRService unhcrService;
    private final CacheWarmupService cacheWarmupService;
    private final ObjectMapper objectMapper;
    private final RiskScoreService riskScoreService;
    private final NowcastService nowcastService;
    private final HungerMapService hungerMapService;
    private final CurrencyService currencyService;
    private final WHODiseaseOutbreakService whoDiseaseOutbreakService;
    private final SituationDetectionService situationDetectionService;
    private final StoryService storyService;
    private final FAOFoodPriceService faoFoodPriceService;

    @Value("${DASHSCOPE_API_KEY:}")
    private String apiKey;

    private final String model = "qwen3.5-plus";

    private final WebClient qwenClient = WebClient.builder()
            .baseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
            .build();

    private final WebClient bingClient = WebClient.builder().build();

    // Temporary storage for narrative between generateKeyDevelopments and generateReport
    private volatile String lastNarrative;

    // Topic to search keywords mapping
    private static final Map<String, List<String>> TOPIC_KEYWORDS = new LinkedHashMap<>();
    static {
        TOPIC_KEYWORDS.put("migration", Arrays.asList(
            "migration", "migrants", "refugees", "displaced", "asylum", "border crossing",
            "darien", "deportation", "returnees", "human mobility", "TPS", "exodus"
        ));
        TOPIC_KEYWORDS.put("food-crisis", Arrays.asList(
            "famine", "hunger", "food insecurity", "malnutrition", "starvation",
            "food crisis", "IPC", "food aid", "WFP"
        ));
        TOPIC_KEYWORDS.put("conflict", Arrays.asList(
            "conflict", "violence", "attack", "clashes", "fighting", "military",
            "armed groups", "casualties", "ceasefire", "peace talks"
        ));
        TOPIC_KEYWORDS.put("political", Arrays.asList(
            "political", "government", "president", "election", "coup", "transition",
            "opposition", "sanctions", "diplomatic", "policy"
        ));
        TOPIC_KEYWORDS.put("climate", Arrays.asList(
            "flood", "drought", "cyclone", "hurricane", "earthquake", "climate",
            "disaster", "storm", "landslide", "weather"
        ));
        TOPIC_KEYWORDS.put("health", Arrays.asList(
            "outbreak", "epidemic", "disease", "cholera", "health emergency",
            "vaccination", "hospital", "medical", "WHO"
        ));
        TOPIC_KEYWORDS.put("economic", Arrays.asList(
            "economic crisis", "inflation", "currency", "unemployment", "poverty",
            "fuel shortage", "sanctions", "financial", "prices"
        ));
    }

    // Topic-specific analytical prompts for Claude
    private static final Map<String, String> TOPIC_PROMPTS = new LinkedHashMap<>();
    static {
        TOPIC_PROMPTS.put("conflict", """
            You are a senior conflict analyst at a global intelligence platform.
            Analyze the data below. Focus on:
            - Which conflicts are ESCALATING vs de-escalating (use risk scores and GDELT z-scores)
            - Connections between conflicts in the region (spillover, proxy dynamics)
            - Read between the lines of news headlines — what do they reveal about trajectory?
            - Cascade risks: how could one conflict trigger others?
            Write 3-5 analytical bullets. Be specific with data. Think like an analyst who needs to brief a decision-maker.""");

        TOPIC_PROMPTS.put("food-crisis", """
            You are a food security analyst with access to proprietary nowcasting data.
            Analyze the data below. Focus on:
            - Which countries are on a WORSENING trajectory (use our 90-day nowcast predictions)
            - The gap between current food insecurity levels and projected levels
            - Seasonal factors (lean season, harvest, monsoon) that could accelerate deterioration
            - Cross-cutting risks: conflict-driven hunger, climate-driven crop failure, economic-driven food inflation
            Write 3-5 analytical bullets. Cite the nowcast predictions as evidence — this is unique data no one else has.""");

        TOPIC_PROMPTS.put("climate", """
            You are a climate-humanitarian analyst.
            Analyze the data below. Focus on:
            - Drought vs flood risk balance across the region
            - Impact on food production and vulnerable populations
            - Compounding dynamics: climate + conflict = accelerated displacement
            - Seasonal outlook: what's coming in the next 30-90 days?
            Write 3-5 analytical bullets. Use precipitation anomaly % and NDVI data as evidence.""");

        TOPIC_PROMPTS.put("health", """
            You are an epidemiological intelligence analyst.
            Analyze the data below. Focus on:
            - Active outbreak trajectory: expanding, contained, or unclear?
            - Cross-border transmission risk
            - Healthcare system capacity in affected countries
            - Interaction with conflict/displacement (disease in camp settings)
            Write 3-5 analytical bullets. Reference specific outbreaks and affected countries.""");

        TOPIC_PROMPTS.put("economic", """
            You are a macro-economic crisis analyst focused on humanitarian impact.
            Analyze the data below. Focus on:
            - Currency devaluation velocity and its impact on food imports
            - Which countries are in economic free-fall vs gradual decline?
            - Sanctions, trade disruptions, or structural factors driving the crisis
            - Cascading effects: currency collapse → food inflation → food insecurity
            Write 3-5 analytical bullets. Cite currency change percentages and economic risk scores.""");

        TOPIC_PROMPTS.put("political", """
            You are a political risk analyst.
            Analyze the data below. Focus on:
            - Governance instability: coup risk, election violence, regime fragility
            - Political factors driving humanitarian crises
            - Sanctions, diplomatic isolation, or international pressure
            - How political dynamics interact with conflict, food security, and displacement
            Write 3-5 analytical bullets. Ground analysis in specific events and countries.""");

        TOPIC_PROMPTS.put("migration", """
            You are a humanitarian intelligence analyst specializing in displacement.
            Analyze the data below. Focus on:
            - Major displacement stocks and any available flow indicators
            - Drivers of displacement: conflict, climate, economic
            - Protection risks for displaced populations
            - Regional dynamics: cross-border movement patterns
            Write 3-5 analytical bullets. Cite IDP/refugee numbers where available.""");
    }

    // ReliefWeb themes for topic filtering
    private static final Map<String, List<String>> TOPIC_RELIEFWEB_THEMES = new LinkedHashMap<>();
    static {
        TOPIC_RELIEFWEB_THEMES.put("migration", Arrays.asList("Population Movement", "Protection and Human Rights"));
        TOPIC_RELIEFWEB_THEMES.put("food-crisis", Arrays.asList("Food and Nutrition"));
        TOPIC_RELIEFWEB_THEMES.put("conflict", Arrays.asList("Protection and Human Rights", "Peace and Security"));
        TOPIC_RELIEFWEB_THEMES.put("health", Arrays.asList("Health"));
        TOPIC_RELIEFWEB_THEMES.put("climate", Arrays.asList("Climate Change and Environment", "Disaster Management"));
    }

    // Region to countries mapping (comprehensive)
    private static final Map<String, List<String>> REGION_COUNTRIES = new LinkedHashMap<>();
    static {
        // Americas
        REGION_COUNTRIES.put("lac", Arrays.asList(
            "VEN", "COL", "HTI", "CUB", "GTM", "HND", "SLV", "NIC", "PAN", "ECU", "PER", "BOL", "BRA"
        ));
        REGION_COUNTRIES.put("central-america", Arrays.asList(
            "GTM", "HND", "SLV", "NIC", "PAN", "CRI", "BLZ"
        ));
        REGION_COUNTRIES.put("caribbean", Arrays.asList(
            "HTI", "DOM", "CUB", "JAM", "TTO"
        ));
        REGION_COUNTRIES.put("south-america", Arrays.asList(
            "VEN", "COL", "ECU", "PER", "BOL", "BRA", "ARG", "CHL", "PRY", "URY"
        ));

        // Middle East & North Africa
        REGION_COUNTRIES.put("mena", Arrays.asList(
            "SYR", "YEM", "LBN", "IRQ", "PSE", "LBY", "IRN", "JOR", "EGY"
        ));
        REGION_COUNTRIES.put("middle-east", Arrays.asList(
            "SYR", "YEM", "LBN", "IRQ", "PSE", "JOR", "IRN"
        ));

        // Africa
        REGION_COUNTRIES.put("east-africa", Arrays.asList(
            "ETH", "SOM", "SSD", "SDN", "KEN", "UGA", "TZA", "RWA", "BDI", "ERI", "DJI"
        ));
        REGION_COUNTRIES.put("horn-of-africa", Arrays.asList(
            "ETH", "SOM", "SSD", "SDN", "ERI", "DJI"
        ));
        REGION_COUNTRIES.put("west-africa", Arrays.asList(
            "NGA", "NER", "MLI", "BFA", "TCD", "CAF", "CMR", "GHA", "SEN", "MRT"
        ));
        REGION_COUNTRIES.put("sahel", Arrays.asList(
            "MLI", "NER", "BFA", "TCD", "MRT", "SEN", "NGA"
        ));
        REGION_COUNTRIES.put("central-africa", Arrays.asList(
            "COD", "CAF", "CMR", "GAB", "COG", "GNQ"
        ));

        // Asia
        REGION_COUNTRIES.put("asia", Arrays.asList(
            "AFG", "PAK", "BGD", "MMR", "NPL", "LKA", "PHL", "IDN"
        ));
        REGION_COUNTRIES.put("central-asia", Arrays.asList(
            "AFG", "TJK", "UZB", "KGZ", "TKM", "KAZ"
        ));
        REGION_COUNTRIES.put("southeast-asia", Arrays.asList(
            "MMR", "THA", "VNM", "KHM", "LAO", "MYS", "IDN", "PHL"
        ));
        REGION_COUNTRIES.put("south-asia", Arrays.asList(
            "AFG", "PAK", "BGD", "NPL", "LKA", "IND"
        ));

        // Europe
        REGION_COUNTRIES.put("europe", Arrays.asList(
            "UKR", "MDA", "GEO", "ARM", "AZE"
        ));
        REGION_COUNTRIES.put("eastern-europe", Arrays.asList(
            "UKR", "MDA", "BLR", "RUS"
        ));
    }

    // Country name mappings
    private static final Map<String, String> ISO3_TO_NAME = new HashMap<>();
    static {
        ISO3_TO_NAME.put("VEN", "Venezuela");
        ISO3_TO_NAME.put("COL", "Colombia");
        ISO3_TO_NAME.put("HTI", "Haiti");
        ISO3_TO_NAME.put("CUB", "Cuba");
        ISO3_TO_NAME.put("GTM", "Guatemala");
        ISO3_TO_NAME.put("HND", "Honduras");
        ISO3_TO_NAME.put("SLV", "El Salvador");
        ISO3_TO_NAME.put("NIC", "Nicaragua");
        ISO3_TO_NAME.put("PAN", "Panama");
        ISO3_TO_NAME.put("ECU", "Ecuador");
        ISO3_TO_NAME.put("PER", "Peru");
        ISO3_TO_NAME.put("BOL", "Bolivia");
        ISO3_TO_NAME.put("BRA", "Brazil");
        ISO3_TO_NAME.put("SYR", "Syria");
        ISO3_TO_NAME.put("YEM", "Yemen");
        ISO3_TO_NAME.put("LBN", "Lebanon");
        ISO3_TO_NAME.put("IRQ", "Iraq");
        ISO3_TO_NAME.put("PSE", "Palestine");
        ISO3_TO_NAME.put("LBY", "Libya");
        ISO3_TO_NAME.put("IRN", "Iran");
        ISO3_TO_NAME.put("ETH", "Ethiopia");
        ISO3_TO_NAME.put("SOM", "Somalia");
        ISO3_TO_NAME.put("SSD", "South Sudan");
        ISO3_TO_NAME.put("SDN", "Sudan");
        ISO3_TO_NAME.put("KEN", "Kenya");
        ISO3_TO_NAME.put("UGA", "Uganda");
        ISO3_TO_NAME.put("NGA", "Nigeria");
        ISO3_TO_NAME.put("NER", "Niger");
        ISO3_TO_NAME.put("MLI", "Mali");
        ISO3_TO_NAME.put("BFA", "Burkina Faso");
        ISO3_TO_NAME.put("TCD", "Chad");
        ISO3_TO_NAME.put("CAF", "Central African Republic");
        ISO3_TO_NAME.put("AFG", "Afghanistan");
        ISO3_TO_NAME.put("PAK", "Pakistan");
        ISO3_TO_NAME.put("BGD", "Bangladesh");
        ISO3_TO_NAME.put("MMR", "Myanmar");
        ISO3_TO_NAME.put("UKR", "Ukraine");
        ISO3_TO_NAME.put("COD", "DR Congo");
        ISO3_TO_NAME.put("MOZ", "Mozambique");
    }

    /**
     * Generate intelligence-grade topic report
     */
    public IntelligenceReport generateReport(String topic, String region, int days) {
        log.info("Generating intelligence report: topic={}, region={}, days={}", topic, region, days);

        // Validate days parameter
        if (days < 1) days = 7;
        if (days > 90) days = 90;

        IntelligenceReport report = new IntelligenceReport();
        report.setTopic(topic);
        report.setRegion(region);
        report.setDays(days);
        report.setGeneratedAt(LocalDate.now().toString());
        report.setPeriodStart(LocalDate.now().minusDays(days).format(DateTimeFormatter.ofPattern("MMM d")));
        report.setPeriodEnd(LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy")));

        // Get topic keywords - log warning if topic not found
        List<String> keywords;
        if (TOPIC_KEYWORDS.containsKey(topic)) {
            keywords = TOPIC_KEYWORDS.get(topic);
        } else {
            log.warn("Unknown topic '{}', using as literal keyword", topic);
            keywords = Collections.singletonList(topic);
        }

        // Get region countries - or single country if ISO3
        List<String> targetCountries;
        if (region != null && !region.isEmpty()) {
            if (REGION_COUNTRIES.containsKey(region)) {
                targetCountries = REGION_COUNTRIES.get(region);
                log.info("Region '{}' has {} countries", region, targetCountries.size());
            } else if (region.length() == 3 && region.equals(region.toLowerCase())) {
                // Single country ISO3 (e.g., "irn", "sdn")
                targetCountries = Collections.singletonList(region.toUpperCase());
                log.info("Single country report: {}", region.toUpperCase());
            } else {
                log.warn("Unknown region '{}', using global default countries", region);
                targetCountries = Collections.emptyList();
            }
        } else {
            targetCountries = Collections.emptyList();
        }

        // Default countries if no region specified or region unknown
        List<String> countriesToSearch = targetCountries.isEmpty()
            ? Arrays.asList("VEN", "COL", "HTI", "SYR", "YEM", "SDN", "SSD", "ETH", "SOM", "AFG", "UKR")
            : targetCountries;

        // ========================================
        // LAYER 2: Collect Operational Data
        // ========================================

        // Get DTM IDP data for region
        Map<String, Long> countryIdps = new HashMap<>();
        Map<String, Long> countryRefugees = new HashMap<>();

        try {
            List<MobilityStock> dtmData = dtmService.getCountryLevelIdps();
            for (MobilityStock stock : dtmData) {
                if (countriesToSearch.contains(stock.getIso3())) {
                    countryIdps.put(stock.getIso3(), stock.getIdps());
                }
            }
        } catch (Exception e) {
            log.debug("Error fetching DTM data: {}", e.getMessage());
        }

        // Get UNHCR refugee data
        try {
            List<MobilityStock> unhcrData = unhcrService.getDisplacementByOrigin();
            for (MobilityStock stock : unhcrData) {
                if (countriesToSearch.contains(stock.getIso3())) {
                    countryRefugees.put(stock.getIso3(), stock.getRefugees());
                }
            }
        } catch (Exception e) {
            log.debug("Error fetching UNHCR data: {}", e.getMessage());
        }

        // ========================================
        // LAYER 1: Collect Media Signals
        // ========================================

        Map<String, CountryData> countryDataMap = new LinkedHashMap<>();
        List<SourceItem> allSources = new ArrayList<>();

        // Build GDELT query keywords from topic (use OR syntax for GDELT)
        String gdeltKeywords = String.join(" OR ", keywords);
        log.info("Using GDELT keywords for {}: {}", topic, gdeltKeywords);

        // Track if we're limiting data
        int maxReliefWebCountries = 5;  // Reduced to avoid timeout
        boolean isDataLimited = false;

        // Use ONLY cached GDELT spikes — NEVER make live GDELT API calls from topic reports
        // Live calls cause 15s × N rate-limited waits that hang the request
        try {
            @SuppressWarnings("unchecked")
            List<MediaSpike> cachedSpikes = cacheWarmupService.getFallback("gdeltAllSpikes");
            if (cachedSpikes != null) {
                for (var spike : cachedSpikes) {
                    String iso3 = spike.getIso3();
                    if (!countriesToSearch.contains(iso3)) continue;

                    CountryData cd = countryDataMap.computeIfAbsent(iso3, k -> new CountryData(iso3));
                    cd.mediaCount = spike.getArticlesLast7Days() != null ? spike.getArticlesLast7Days() : 0;

                    if (spike.getTopHeadlines() != null) {
                        for (String headline : spike.getTopHeadlines()) {
                            if (matchesTopic(headline, keywords)) {
                                cd.headlines.add(headline);
                                allSources.add(new SourceItem(
                                    "GDELT",
                                    headline,
                                    null,
                                    "GDELT",
                                    LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d"))
                                ));
                            }
                        }
                    }
                }
            } else {
                log.info("GDELT cache not ready, skipping GDELT data for topic report");
            }
        } catch (Exception e) {
            log.warn("Error reading cached GDELT data: {}", e.getMessage());
        }

        // Sources come from GDELT spikes (already in memory fallback) — NO live API calls
        // Additional sources populated in buildTopicSpecificContext via risk scores, nowcast, etc.
        log.info("Sources from GDELT headlines: {}, building report with topic-specific data", allSources.size());

        // ========================================
        // LAYER 4: Build Country Matrix (Stock vs Flow)
        // ========================================

        List<CountryMetrics> countryMatrix = new ArrayList<>();
        int totalMedia = 0;
        int totalReports = 0;
        long totalIdps = 0;
        long totalRefugees = 0;
        boolean anyFlowDelta = false;  // Track if ANY country has actual flow delta

        for (String iso3 : countriesToSearch) {
            CountryData cd = countryDataMap.getOrDefault(iso3, new CountryData(iso3));
            Long idps = countryIdps.get(iso3);
            Long refugees = countryRefugees.get(iso3);

            // Skip countries with no data
            if (cd.mediaCount == 0 && cd.reportsCount == 0 && idps == null && refugees == null) {
                continue;
            }

            CountryMetrics metrics = new CountryMetrics();
            metrics.setCountry(ISO3_TO_NAME.getOrDefault(iso3, iso3));
            metrics.setIso3(iso3);
            metrics.setMediaCount(cd.mediaCount);
            metrics.setReportsCount(cd.reportsCount);
            metrics.setSignalCount(cd.mediaCount + cd.reportsCount);

            // STOCK data (latest snapshot - NOT flow)
            if (idps != null && idps > 0) {
                metrics.setIdps(idps);
                metrics.setStockData(formatNumber(idps) + " IDPs");
                metrics.setHasStock(true);
            } else if (refugees != null && refugees > 0) {
                metrics.setRefugees(refugees);
                metrics.setStockData(formatNumber(refugees) + " refugees");
                metrics.setHasStock(true);
            } else {
                metrics.setStockData("—");
                metrics.setHasStock(false);
            }

            // FLOW DELTA (WoW/MoM change)
            // Currently unavailable - would need DTM flow monitoring API, border crossing data
            // Be EXPLICIT about why we don't have this data
            metrics.setFlowDelta("No data");  // Clear message, not cryptic "n/a"
            metrics.setHasFlowDelta(false);

            // TREND: Requires flow delta to calculate
            // Without movement data, we CANNOT claim increasing/decreasing/stable
            if (metrics.isHasFlowDelta()) {
                // Would calculate based on actual flow change %
                // e.g., if flowDelta > +10% -> "increasing"
                // if flowDelta < -10% -> "decreasing"
                // else -> "stable"
                metrics.setTrend("stable");
                anyFlowDelta = true;
            } else {
                // Be explicit: trend requires flow data
                metrics.setTrend("No trend data");
            }

            // CONFIDENCE: Based on data completeness
            // Without flow data, confidence is "signal_only" (honest about limitations)
            metrics.setConfidence(calculateStrictConfidence(
                metrics.isHasFlowDelta(),
                cd.mediaCount,
                cd.reportsCount
            ));

            // Top headline
            if (!cd.headlines.isEmpty()) {
                String headline = cd.headlines.get(0);
                metrics.setTopHeadline(headline.length() > 100 ? headline.substring(0, 97) + "..." : headline);
            }

            countryMatrix.add(metrics);
            totalMedia += cd.mediaCount;
            totalReports += cd.reportsCount;
            if (idps != null) totalIdps += idps;
            if (refugees != null) totalRefugees += refugees;
        }

        // Sort by signal count (media + reports), then by stock
        countryMatrix.sort((a, b) -> {
            int signalDiff = b.getSignalCount() - a.getSignalCount();
            if (signalDiff != 0) return signalDiff;
            // Secondary sort by stock presence
            return Boolean.compare(b.isHasStock(), a.isHasStock());
        });
        report.setCountryMatrix(countryMatrix);

        // ========================================
        // Calculate Overall Trend & Coverage Quality
        // ========================================

        // Overall trend: use flow data if available, fall back to risk scores for high-conflict countries
        String overallTrend;
        @SuppressWarnings("unchecked")
        List<com.crisismonitor.model.RiskScore> trendScores = cacheWarmupService.getFallback("allRiskScores");
        boolean hasHighConflict = trendScores != null && trendScores.stream()
            .filter(rs -> countriesToSearch.contains(rs.getIso3()))
            .anyMatch(rs -> rs.getConflictScore() >= 50);
        if (!anyFlowDelta && !hasHighConflict) {
            overallTrend = "signal_only";  // Honest: we have signal but no flow data to compute trend
        } else if (!anyFlowDelta && hasHighConflict) {
            // No flow data but high conflict — active war, not "signal only"
            boolean anyCritical = trendScores.stream()
                .filter(rs -> countriesToSearch.contains(rs.getIso3()))
                .anyMatch(rs -> rs.getScore() >= 60);
            overallTrend = anyCritical ? "critical" : "increasing";
        } else {
            int trendingUp = (int) countryMatrix.stream().filter(c -> "increasing".equals(c.getTrend())).count();
            int trendingDown = (int) countryMatrix.stream().filter(c -> "decreasing".equals(c.getTrend())).count();
            if (trendingUp > trendingDown && trendingUp >= 2) {
                overallTrend = "increasing";
            } else if (trendingDown > trendingUp && trendingDown >= 2) {
                overallTrend = "decreasing";
            } else {
                overallTrend = "stable";
            }
        }
        report.setOverallTrend(overallTrend);

        // Regional summary with coverage quality assessment
        RegionalSummary summary = new RegionalSummary();
        summary.setTotalCountries(countryMatrix.size());
        summary.setTotalMedia(totalMedia);
        summary.setTotalReports(totalReports);
        summary.setTotalIdps(totalIdps > 0 ? formatNumber(totalIdps) : null);
        summary.setTotalRefugees(totalRefugees > 0 ? formatNumber(totalRefugees) : null);
        summary.setHasFlowData(anyFlowDelta);

        // Coverage quality: HIGH (flow + multi-source), MEDIUM (multi-source), LIMITED (sparse)
        String coverageQuality;
        if (anyFlowDelta && (totalMedia + totalReports) >= 5) {
            coverageQuality = "HIGH";
        } else if ((totalMedia + totalReports) >= 3) {
            coverageQuality = "MEDIUM";
        } else {
            coverageQuality = "LIMITED";
        }
        summary.setCoverageQuality(coverageQuality);

        // Data limitation notice if region has more countries than we queried
        if (countriesToSearch.size() > maxReliefWebCountries) {
            summary.setDataLimitation(String.format(
                "Showing %d of %d countries in region",
                maxReliefWebCountries,
                countriesToSearch.size()
            ));
        }
        report.setRegionalSummary(summary);

        // ========================================
        // LAYER 3: AI Synthesis (Key Developments)
        // ========================================

        List<KeyDevelopment> keyDevelopments = generateKeyDevelopments(topic, region, countryMatrix, allSources, countriesToSearch);
        report.setKeyDevelopments(keyDevelopments);
        if (lastNarrative != null && !lastNarrative.isBlank()) {
            report.setNarrative(lastNarrative);
            lastNarrative = null;
        }

        // Dedupe and limit sources
        Set<String> seenUrls = new HashSet<>();
        List<SourceItem> uniqueSources = allSources.stream()
            .filter(s -> s.getUrl() != null && seenUrls.add(s.getUrl()))
            .limit(25)
            .collect(Collectors.toList());
        report.setSources(uniqueSources);

        log.info("Generated intelligence report: {} countries, {} media, {} reports, trend={}",
            countryMatrix.size(), totalMedia, totalReports, overallTrend);

        return report;
    }

    /**
     * Build topic-specific context by pulling data from relevant services.
     */
    private String buildTopicSpecificContext(String topic, String region, List<String> countries,
            List<CountryMetrics> countryMatrix, List<SourceItem> sources) {

        StringBuilder ctx = new StringBuilder();
        ctx.append("Topic: ").append(topic.toUpperCase()).append("\n");
        ctx.append("Region: ").append(region != null ? region.toUpperCase() : "Global").append("\n");
        ctx.append("Date: ").append(LocalDate.now()).append("\n\n");

        // Inject verified conflict info for countries in this report
        Map<String, String> verifiedConflicts = Map.ofEntries(
            Map.entry("PSE", "Active war: Israeli military operations in Gaza, siege, ground invasion"),
            Map.entry("IRN", "Active war: US/Israel-Iran military conflict since Feb 2026, Hormuz blockade, ongoing airstrikes"),
            Map.entry("UKR", "Active war: Russia-Ukraine full-scale invasion since Feb 2022"),
            Map.entry("SDN", "Civil war: SAF vs RSF, 150K+ estimated dead, widespread displacement"),
            Map.entry("ISR", "Multi-front conflict: Gaza operations + Iran war"),
            Map.entry("LBN", "Post-2024 war, fragile ceasefire, border tensions"),
            Map.entry("MMR", "Nationwide civil war: resistance forces vs military junta"),
            Map.entry("SYR", "Multi-front civil war, post-Assad transition instability"),
            Map.entry("YEM", "Houthi conflict + US strikes + Red Sea/Hormuz maritime operations")
        );
        boolean hasVerified = false;
        for (String iso3 : countries) {
            String info = verifiedConflicts.get(iso3);
            if (info != null) {
                if (!hasVerified) {
                    ctx.append("VERIFIED ARMED CONFLICTS (analyst-confirmed ground truth):\n");
                    hasVerified = true;
                }
                ctx.append("  ").append(com.crisismonitor.config.MonitoredCountries.getName(iso3))
                   .append(": ").append(info).append("\n");
            }
        }
        if (hasVerified) ctx.append("\n");

        // Load risk scores once (used by multiple topics)
        @SuppressWarnings("unchecked")
        List<com.crisismonitor.model.RiskScore> riskScores = cacheWarmupService.getFallback("allRiskScores");

        try {
            switch (topic) {
                case "conflict" -> {
                    // Pull risk scores with conflict detail
                    // riskScores loaded before switch
                    if (riskScores != null) {
                        ctx.append("RISK SCORES (conflict focus):\n");
                        riskScores.stream()
                            .filter(rs -> countries.contains(rs.getIso3()))
                            .sorted((a, b) -> b.getConflictScore() - a.getConflictScore())
                            .limit(10)
                            .forEach(rs -> {
                                ctx.append(String.format("  %s: overall=%d/100 (%s), conflict=%d, food=%d, climate=%d, econ=%d, trend=%s%s\n",
                                    rs.getCountryName(), rs.getScore(), rs.getRiskLevel(),
                                    rs.getConflictScore(), rs.getFoodSecurityScore(), rs.getClimateScore(), rs.getEconomicScore(),
                                    rs.getTrendIcon() != null ? rs.getTrendIcon() : "—",
                                    rs.getGdeltZScore() != null ? String.format(", mediaZ=%.1f", rs.getGdeltZScore()) : ""));
                                if (rs.getSummary() != null) ctx.append("    AI: ").append(rs.getSummary()).append("\n");
                            });
                    }

                    // Active situations (from warmup fallback)
                    try {
                        @SuppressWarnings("unchecked")
                        var sitReport = (SituationDetectionService.SituationReport) cacheWarmupService.getFallback("activeSituations");
                        if (sitReport != null && sitReport.getSituations() != null) {
                            ctx.append("\nACTIVE CRISIS SITUATIONS:\n");
                            sitReport.getSituations().stream().limit(5).forEach(sit ->
                                ctx.append("  - ").append(sit.getCountryName() != null ? sit.getCountryName() : "").append(": ").append(sit.getSituationLabel() != null ? sit.getSituationLabel() : "").append(" (").append(sit.getSeverity() != null ? sit.getSeverity() : "").append(")\n"));
                        }
                    } catch (Exception e) { /* skip */ }

                    // GDELT spikes
                    @SuppressWarnings("unchecked")
                    List<MediaSpike> spikes = cacheWarmupService.getFallback("gdeltAllSpikes");
                    if (spikes != null) {
                        ctx.append("\nMEDIA CONFLICT SPIKES (GDELT):\n");
                        spikes.stream()
                            .filter(s -> countries.contains(s.getIso3()) && s.getZScore() != null && s.getZScore() > 0.5)
                            .sorted((a, b) -> Double.compare(b.getZScore(), a.getZScore()))
                            .limit(8)
                            .forEach(s -> ctx.append(String.format("  %s: z=%.1f, articles7d=%d, spike=%s\n",
                                s.getIso3(), s.getZScore(), s.getArticlesLast7Days() != null ? s.getArticlesLast7Days() : 0,
                                s.getSpikeLevel() != null ? s.getSpikeLevel() : "none")));
                    }
                }

                case "food-crisis" -> {
                    // FAO Food Price Index (global context)
                    try {
                        var faoLatest = faoFoodPriceService.getLatest();
                        if (faoLatest != null) {
                            ctx.append("GLOBAL FOOD PRICES (FAO FFPI, base 2014-2016=100):\n");
                            ctx.append(String.format("  Food Index: %.1f", faoLatest.getFoodIndex()));
                            if (faoLatest.getFoodYoY() != null) ctx.append(String.format(" (YoY: %+.1f%%)", faoLatest.getFoodYoY()));
                            ctx.append(String.format("\n  Cereals: %.1f", faoLatest.getCerealsIndex()));
                            if (faoLatest.getCerealsYoY() != null) ctx.append(String.format(" (YoY: %+.1f%%)", faoLatest.getCerealsYoY()));
                            ctx.append(String.format("\n  Oils: %.1f, Dairy: %.1f, Meat: %.1f, Sugar: %.1f\n",
                                faoLatest.getOilsIndex(), faoLatest.getDairyIndex(), faoLatest.getMeatIndex(), faoLatest.getSugarIndex()));
                            ctx.append("  Date: ").append(faoLatest.getDate()).append("\n\n");
                        }
                    } catch (Exception e) { /* skip */ }

                    // Nowcast predictions (our unique data)
                    var nowcasts = nowcastService.getNowcastAll();
                    if (nowcasts != null && !nowcasts.isEmpty()) {
                        ctx.append("FOOD INSECURITY NOWCAST (90-day predictions, proprietary model):\n");
                        nowcasts.stream()
                            .filter(n -> countries.isEmpty() || countries.contains(n.getIso3()))
                            .sorted((a, b) -> Double.compare(
                                b.getPredictedChange90d() != null ? b.getPredictedChange90d() : 0,
                                a.getPredictedChange90d() != null ? a.getPredictedChange90d() : 0))
                            .limit(12)
                            .forEach(n -> ctx.append(String.format("  %s: current=%.1f%%, predicted90d=%+.1f%%, projected=%.1f%%, trend=%s, FCS=%.1f%%, rCSI=%s\n",
                                n.getCountryName(),
                                n.getCurrentProxy() != null ? n.getCurrentProxy() : 0,
                                n.getPredictedChange90d() != null ? n.getPredictedChange90d() : 0,
                                n.getProjectedProxy() != null ? n.getProjectedProxy() : 0,
                                n.getTrend() != null ? n.getTrend() : "—",
                                n.getFcsPrevalence() != null ? n.getFcsPrevalence() : 0,
                                n.getRcsiPrevalence() != null ? String.format("%.1f%%", n.getRcsiPrevalence()) : "N/A")));
                    }

                    // Risk scores food component
                    // riskScores loaded before switch
                    if (riskScores != null) {
                        ctx.append("\nFOOD SECURITY RISK SCORES:\n");
                        riskScores.stream()
                            .filter(rs -> countries.contains(rs.getIso3()) && rs.getFoodSecurityScore() > 30)
                            .sorted((a, b) -> b.getFoodSecurityScore() - a.getFoodSecurityScore())
                            .limit(8)
                            .forEach(rs -> ctx.append(String.format("  %s: food=%d/100, overall=%d (%s)\n",
                                rs.getCountryName(), rs.getFoodSecurityScore(), rs.getScore(), rs.getRiskLevel())));
                    }
                }

                case "climate" -> {
                    // Risk scores climate component
                    // riskScores loaded before switch
                    if (riskScores != null) {
                        ctx.append("CLIMATE RISK SCORES:\n");
                        riskScores.stream()
                            .filter(rs -> countries.contains(rs.getIso3()) && rs.getClimateScore() > 15)
                            .sorted((a, b) -> b.getClimateScore() - a.getClimateScore())
                            .limit(10)
                            .forEach(rs -> {
                                ctx.append(String.format("  %s: climate=%d/100", rs.getCountryName(), rs.getClimateScore()));
                                if (rs.getPrecipitationAnomaly() != null)
                                    ctx.append(String.format(", precipAnomaly=%+.0f%%", rs.getPrecipitationAnomaly()));
                                ctx.append(String.format(", overall=%d (%s)\n", rs.getScore(), rs.getRiskLevel()));
                            });
                    }
                }

                case "health" -> {
                    // WHO outbreaks (from warmup fallback)
                    try {
                        @SuppressWarnings("unchecked")
                        var outbreaks = (List<WHODiseaseOutbreakService.DiseaseOutbreak>) cacheWarmupService.getFallback("whoDiseaseOutbreaks");
                        if (outbreaks != null) {
                            ctx.append("WHO DISEASE OUTBREAKS:\n");
                            outbreaks.stream().limit(10).forEach(o ->
                                ctx.append(String.format("  - %s (%s) — %s\n",
                                    o.getTitle() != null ? o.getTitle() : "Unknown",
                                    o.getCountryIso3() != null ? o.getCountryIso3() : "Global",
                                    o.getTimeAgo() != null ? o.getTimeAgo() : "recent")));
                        }
                    } catch (Exception e) { /* skip */ }
                }

                case "economic" -> {
                    // FAO Food Price Index (global food inflation context)
                    try {
                        var faoLatest = faoFoodPriceService.getLatest();
                        if (faoLatest != null) {
                            ctx.append("GLOBAL FOOD PRICES (FAO FFPI):\n");
                            ctx.append(String.format("  Food Index: %.1f", faoLatest.getFoodIndex()));
                            if (faoLatest.getFoodYoY() != null) ctx.append(String.format(" (YoY: %+.1f%%)", faoLatest.getFoodYoY()));
                            ctx.append(String.format("\n  Cereals: %.1f, Oils: %.1f (%s)\n\n",
                                faoLatest.getCerealsIndex(), faoLatest.getOilsIndex(), faoLatest.getDate()));
                        }
                    } catch (Exception e) { /* skip */ }

                    // Risk scores economic component
                    // riskScores loaded before switch
                    if (riskScores != null) {
                        ctx.append("ECONOMIC RISK SCORES:\n");
                        riskScores.stream()
                            .filter(rs -> countries.contains(rs.getIso3()) && rs.getEconomicScore() > 15)
                            .sorted((a, b) -> b.getEconomicScore() - a.getEconomicScore())
                            .limit(10)
                            .forEach(rs -> {
                                ctx.append(String.format("  %s: econ=%d/100", rs.getCountryName(), rs.getEconomicScore()));
                                if (rs.getCurrencyChange30d() != null)
                                    ctx.append(String.format(", currency30d=%+.1f%%", rs.getCurrencyChange30d()));
                                ctx.append(String.format(", overall=%d (%s)\n", rs.getScore(), rs.getRiskLevel()));
                            });
                    }
                }

                case "political" -> {
                    // Political: risk scores + active situations
                    if (riskScores != null) {
                        ctx.append("RISK SCORES (political context):\n");
                        riskScores.stream()
                            .filter(rs -> countries.contains(rs.getIso3()))
                            .sorted((a, b) -> b.getScore() - a.getScore())
                            .limit(10)
                            .forEach(rs -> ctx.append(String.format("  %s: overall=%d/100 (%s), conflict=%d, food=%d, econ=%d, trend=%s\n",
                                rs.getCountryName(), rs.getScore(), rs.getRiskLevel(),
                                rs.getConflictScore(), rs.getFoodSecurityScore(), rs.getEconomicScore(),
                                rs.getTrendIcon() != null ? rs.getTrendIcon() : "—")));
                    }
                    // Active situations
                    try {
                        @SuppressWarnings("unchecked")
                        var sitReport = (SituationDetectionService.SituationReport) cacheWarmupService.getFallback("activeSituations");
                        if (sitReport != null && sitReport.getSituations() != null) {
                            ctx.append("\nACTIVE SITUATIONS:\n");
                            sitReport.getSituations().stream()
                                .filter(s -> countries.contains(s.getIso3()))
                                .limit(5)
                                .forEach(sit -> ctx.append("  - ").append(sit.getCountryName() != null ? sit.getCountryName() : "").append(": ").append(sit.getSituationLabel() != null ? sit.getSituationLabel() : "").append(" (").append(sit.getSeverity() != null ? sit.getSeverity() : "").append(")\n"));
                        }
                    } catch (Exception e) { /* skip */ }
                }

                case "migration" -> {
                    // Migration: IDPs + refugees (the original template makes sense here)
                    ctx.append("DISPLACEMENT DATA:\n");
                    for (CountryMetrics cm : countryMatrix.stream().limit(10).collect(Collectors.toList())) {
                        ctx.append(String.format("  %s: stock=%s, signal=%d\n",
                            cm.getCountry(), cm.getStockData(), cm.getSignalCount()));
                    }
                }

                default -> {
                    // Generic fallback with risk scores
                    if (riskScores != null) {
                        ctx.append("RISK SCORES:\n");
                        riskScores.stream()
                            .filter(rs -> countries.contains(rs.getIso3()))
                            .sorted((a, b) -> b.getScore() - a.getScore())
                            .limit(8)
                            .forEach(rs -> ctx.append(String.format("  %s: score=%d (%s)\n",
                                rs.getCountryName(), rs.getScore(), rs.getRiskLevel())));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error building topic context for {}: {}", topic, e.getMessage());
        }

        // Add real news headlines from cached RSS + ReliefWeb
        @SuppressWarnings("unchecked")
        List<Map<String, String>> cachedHeadlines = cacheWarmupService.getFallback("newsHeadlines");
        if (cachedHeadlines != null && !cachedHeadlines.isEmpty()) {
            // Filter by region — for single-country reports, also match by country name in title
            String regionUpper = region != null ? region.toUpperCase().replace("-", "_").replace(" ", "_") : "";
            boolean isSingleCountry = countries.size() == 1 && region != null && region.length() == 3;
            Set<String> countryNames = isSingleCountry
                ? countries.stream()
                    .map(iso -> com.crisismonitor.config.MonitoredCountries.getName(iso))
                    .filter(Objects::nonNull)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet())
                : Collections.emptySet();

            List<Map<String, String>> regionNews = cachedHeadlines.stream()
                .filter(h -> {
                    String hRegion = h.getOrDefault("region", "");
                    String hIso3 = h.getOrDefault("iso3", "");
                    String hTitle = h.getOrDefault("title", "").toLowerCase();
                    // Match by: region code, iso3, or country name in headline title
                    return regionUpper.isEmpty()
                        || hRegion.toUpperCase().contains(regionUpper.replace("_", ""))
                        || countries.stream().anyMatch(c -> c.equals(hIso3))
                        || countryNames.stream().anyMatch(hTitle::contains);
                })
                .collect(Collectors.toList());

            // Further filter by topic keywords
            List<String> topicKw = TOPIC_KEYWORDS.getOrDefault(topic, Collections.emptyList());
            List<Map<String, String>> topicNews = regionNews.stream()
                .filter(h -> {
                    String title = h.getOrDefault("title", "").toLowerCase();
                    return topicKw.isEmpty() || topicKw.stream().anyMatch(kw -> title.contains(kw.toLowerCase()));
                })
                .collect(Collectors.toList());

            // Use topic-filtered if enough, otherwise use region-filtered
            List<Map<String, String>> newsToUse = topicNews.size() >= 3 ? topicNews : regionNews;

            ctx.append("\nREAL-TIME NEWS (RSS + ReliefWeb):\n");
            newsToUse.stream().limit(12).forEach(h ->
                ctx.append("  [").append(h.getOrDefault("type", "Media")).append("] ")
                   .append(h.getOrDefault("title", "")).append(" (")
                   .append(h.getOrDefault("source", "")).append(")\n"));

            // Also add these as report sources
            newsToUse.stream().limit(15).forEach(h ->
                sources.add(new SourceItem(
                    h.getOrDefault("type", "Media"),
                    h.getOrDefault("title", ""),
                    h.get("url"),
                    h.getOrDefault("source", ""),
                    h.getOrDefault("date", "Recent")
                )));

            log.info("Added {} news headlines to context ({} topic-matched, {} region-matched)",
                newsToUse.size(), topicNews.size(), regionNews.size());
        } else {
            ctx.append("\nNEWS: No cached headlines available yet (warmup in progress)\n");
        }

        return ctx.toString();
    }

    /**
     * Generate key development bullets using Claude AI with topic-specific analysis
     */
    private List<KeyDevelopment> generateKeyDevelopments(String topic, String region,
            List<CountryMetrics> countryMatrix, List<SourceItem> sources,
            List<String> countries) {

        List<KeyDevelopment> developments = new ArrayList<>();

        // Build topic-specific context
        String context = buildTopicSpecificContext(topic, region, countries, countryMatrix, sources);

        // Call Claude with topic-specific prompt
        String synthesis = callClaudeForSynthesis(topic, context);

        String narrative = null;

        if (synthesis != null && !synthesis.isEmpty()) {
            // Split bullets and narrative
            String bulletsPart = synthesis;
            if (synthesis.contains("---NARRATIVE---")) {
                int idx = synthesis.indexOf("---NARRATIVE---");
                bulletsPart = synthesis.substring(0, idx);
                narrative = synthesis.substring(idx + "---NARRATIVE---".length()).trim();
            }

            // Parse bullets
            String[] lines = bulletsPart.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("-") || line.startsWith("•") || line.startsWith("1") || line.startsWith("2") || line.startsWith("3")) {
                    line = line.replaceFirst("^[-•12345]\\.?\\s*", "").trim();
                    if (!line.isEmpty() && developments.size() < 5) {
                        KeyDevelopment kd = new KeyDevelopment();
                        kd.setBullet(line);

                        // Extract country if mentioned
                        for (CountryMetrics cm : countryMatrix) {
                            if (line.toLowerCase().contains(cm.getCountry().toLowerCase())) {
                                kd.setCountry(cm.getCountry());
                                kd.setTrend(cm.getTrend());
                                break;
                            }
                        }

                        // Determine evidence type
                        if (line.toLowerCase().contains("report") || line.toLowerCase().contains("unhcr") || line.toLowerCase().contains("iom")) {
                            kd.setEvidenceType("operational");
                        } else if (line.toLowerCase().contains("%") || line.toLowerCase().contains("idp") || line.toLowerCase().contains("refugee")) {
                            kd.setEvidenceType("flow");
                        } else {
                            kd.setEvidenceType("media");
                        }

                        developments.add(kd);
                    }
                }
            }
        }

        // Fallback: generate from data if Claude fails or returns empty
        if (developments.isEmpty()) {
            for (CountryMetrics cm : countryMatrix.stream().limit(3).collect(Collectors.toList())) {
                KeyDevelopment kd = new KeyDevelopment();
                if (cm.getTopHeadline() != null) {
                    kd.setBullet(cm.getCountry() + ": " + cm.getTopHeadline());
                } else {
                    kd.setBullet(cm.getCountry() + ": " + cm.getMediaCount() + " media mentions, " + cm.getReportsCount() + " reports");
                }
                kd.setCountry(cm.getCountry());
                kd.setTrend(cm.getTrend());
                kd.setEvidenceType(cm.getMediaCount() > 0 ? "media" : "operational");
                developments.add(kd);
            }
        }

        // Store narrative for caller to retrieve
        this.lastNarrative = narrative;
        return developments;
    }

    /**
     * Call Claude API for synthesis
     */
    private String callClaudeForSynthesis(String topic, String context) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("DashScope API key not configured, using fallback synthesis");
            return null;
        }

        // Use topic-specific analytical prompt
        String analyticalLens = TOPIC_PROMPTS.getOrDefault(topic,
            "You are a humanitarian intelligence analyst. Analyze the data below and write 3-5 key analytical bullets.");

        // Sanitize context: remove characters that could break JSON
        String safeContext = context.replace("\\", "\\\\").replace("\"", "'").replace("\t", " ");
        // Truncate if too long (avoid token limits)
        if (safeContext.length() > 6000) {
            safeContext = safeContext.substring(0, 6000) + "\n[...truncated]";
        }

        String userPrompt = """

            APPROACH:
            - You have the DATA below from our real-time monitoring platform
            - You ALSO have your own knowledge of world events, geopolitics, and humanitarian context
            - USE BOTH: combine platform data with your knowledge to produce deep analysis
            - When news headlines reference events (wars, blockades, elections), you know the context — USE IT

            FORMAT — Write in TWO sections:

            SECTION 1 - KEY FINDINGS (3-5 bullets):
            - Start each bullet with "-"
            - Each bullet 100-200 characters — analytical depth, not generic
            - Cite specific numbers from the data

            SECTION 2 - ANALYTICAL NARRATIVE:
            Write a 250-300 word analytical narrative that:
            - Synthesizes all data points into a coherent picture
            - Explains WHY things are happening, not just what
            - Connects dots between countries and indicators
            - Uses your geopolitical knowledge to provide context
            - Identifies trajectory: where is this heading in the next 30-90 days?
            - Reads like a senior analyst briefing, not a news summary
            Start the narrative section with "---NARRATIVE---" on its own line.

            RULES:
            - Be bold in analysis but grounded in evidence
            - NO generic filler like "the situation remains concerning" — be SPECIFIC about WHY
            - Cross-reference platform data with your knowledge to reveal hidden patterns

            DATA:
            """ + safeContext;

        try {
            Map<String, Object> request = new java.util.LinkedHashMap<>();
            request.put("model", "qwen3.5-plus");
            request.put("max_tokens", 1200);
            request.put("enable_search", true);
            request.put("messages", List.of(
                Map.of("role", "system", "content", analyticalLens),
                Map.of("role", "user", "content", userPrompt)
            ));

            String response = qwenClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))
                .block();

            JsonNode root = objectMapper.readTree(response);
            if (root.has("choices") && root.path("choices").size() > 0) {
                return root.path("choices").path(0).path("message").path("content").asText("");
            }
            JsonNode output = root.path("output");
            if (!output.isMissingNode() && output.has("choices") && output.path("choices").size() > 0) {
                return output.path("choices").path(0).path("message").path("content").asText("");
            }
            return null;

        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.warn("Qwen synthesis failed: {} — body: {}", e.getMessage(),
                e.getResponseBodyAsString().substring(0, Math.min(300, e.getResponseBodyAsString().length())));
            return null;
        } catch (Exception e) {
            log.warn("Qwen synthesis failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean matchesTopic(String text, List<String> keywords) {
        if (text == null) return false;
        String lowerText = text.toLowerCase();
        return keywords.stream().anyMatch(k -> lowerText.contains(k.toLowerCase()));
    }

    private boolean matchesReliefWebTheme(ReliefWebService.HumanitarianReport report, String topic) {
        // This would check report.getThemes() if available
        // For now, use keyword fallback
        List<String> themes = TOPIC_RELIEFWEB_THEMES.get(topic);
        if (themes == null) return false;

        String title = report.getTitle();
        if (title == null) return false;

        String lowerTitle = title.toLowerCase();
        for (String theme : themes) {
            if (lowerTitle.contains(theme.toLowerCase().replace(" ", "").substring(0, Math.min(8, theme.length())))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculate HONEST confidence based on available data
     *
     * Rules (strict):
     * - HIGH: Flow delta present AND ≥5 signal sources (media+reports)
     * - MEDIUM: Flow delta present AND ≥2 signal sources
     * - LOW: Flow delta present but sparse signals (<2)
     * - SIGNAL_ONLY: No flow delta (regardless of signal count)
     *
     * Key principle: Without flow data, we CANNOT claim confidence in trends.
     * Signal count alone shows media attention, NOT movement trends.
     */
    private String calculateStrictConfidence(boolean hasFlowDelta, int mediaCount, int reportsCount) {
        int signalCount = mediaCount + reportsCount;

        // Without flow data, we can only report signal strength, not trend confidence
        if (!hasFlowDelta) {
            // Return "signal_only" to indicate we have signals but no trend data
            // This is honest: high signal ≠ high confidence in trend
            return "signal_only";
        }

        // With flow data, confidence depends on corroborating signals
        if (signalCount >= 5) {
            return "high";
        } else if (signalCount >= 2) {
            return "medium";
        }
        return "low";
    }

    private String formatNumber(long num) {
        if (num >= 1_000_000) {
            return String.format("%.1fM", num / 1_000_000.0);
        } else if (num >= 1_000) {
            return String.format("%.0fK", num / 1_000.0);
        }
        return String.valueOf(num);
    }

    // ========================================
    // Internal Data Classes
    // ========================================

    private static class CountryData {
        String iso3;
        int mediaCount = 0;
        int reportsCount = 0;
        List<String> headlines = new ArrayList<>();
        List<String> reports = new ArrayList<>();

        CountryData(String iso3) {
            this.iso3 = iso3;
        }
    }

    // ========================================
    // DTOs
    // ========================================

    @Data
    public static class IntelligenceReport {
        private String topic;
        private String region;
        private int days;
        private String generatedAt;
        private String periodStart;
        private String periodEnd;
        private String overallTrend;  // "increasing", "stable", "decreasing"
        private RegionalSummary regionalSummary;
        private List<KeyDevelopment> keyDevelopments;
        private List<CountryMetrics> countryMatrix;
        private List<SourceItem> sources;
        private String narrative;  // 250-300 word analytical narrative from Claude
    }

    @Data
    public static class RegionalSummary {
        private int totalCountries;
        private int totalMedia;
        private int totalReports;
        private String totalIdps;      // formatted string like "2.3M"
        private String totalRefugees;  // formatted string like "7.7M"
        private boolean hasFlowData;   // true if any country has WoW/MoM flow delta
        private String coverageQuality; // "HIGH", "MEDIUM", "LIMITED"
        private String dataLimitation; // null if full coverage, otherwise explains limitation
    }

    @Data
    public static class KeyDevelopment {
        private String bullet;
        private String country;
        private String trend;          // "increasing", "stable", "decreasing"
        private String evidenceType;   // "media", "operational", "flow"
    }

    @Data
    public static class CountryMetrics {
        private String country;
        private String iso3;
        private int mediaCount;
        private int reportsCount;
        private int signalCount;       // media + reports combined
        private String stockData;      // "1.4M IDPs" or "348K refugees" - latest available
        private boolean hasStock;
        private String flowDelta;      // "+18% WoW" or "n/a" - actual movement change
        private boolean hasFlowDelta;  // true only if we have WoW/MoM change data
        private Long idps;
        private Long refugees;
        private String trend;          // "increasing", "stable", "decreasing", "—" (if no flow)
        private String confidence;     // "high", "medium", "low"
        private String topHeadline;
    }

    @Data
    public static class SourceItem {
        private String type;
        private String title;
        private String url;
        private String publisher;
        private String date;

        public SourceItem(String type, String title, String url, String publisher, String date) {
            this.type = type;
            this.title = title;
            this.url = url;
            this.publisher = publisher;
            this.date = date;
        }
    }

    // ========================================
    // Bing News RSS (fast, no rate limit)
    // ========================================

    private static class BingNewsItem {
        String title;
        String url;
        String source;
    }

    private List<BingNewsItem> fetchBingNews(String query, int limit) {
        List<BingNewsItem> items = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.bing.com/news/search?q=" + encoded + "&format=rss&mkt=en-US";

            String xml = bingClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(8))
                    .block();

            if (xml == null || xml.isBlank()) return items;

            XmlMapper xmlMapper = new XmlMapper();
            xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            NewsAggregatorService.RssFeed feed = xmlMapper.readValue(xml, NewsAggregatorService.RssFeed.class);

            if (feed == null || feed.getChannel() == null || feed.getChannel().getItems() == null) return items;

            Set<String> seen = new HashSet<>();
            for (NewsAggregatorService.RssItem rssItem : feed.getChannel().getItems()) {
                if (items.size() >= limit) break;
                if (rssItem.getTitle() == null || rssItem.getTitle().isBlank()) continue;

                String title = rssItem.getTitle().trim();
                String sourceName = "Bing News";
                int dashIdx = title.lastIndexOf(" - ");
                if (dashIdx > 0 && dashIdx > title.length() - 40) {
                    sourceName = title.substring(dashIdx + 3).trim();
                    title = title.substring(0, dashIdx).trim();
                }

                String key = title.substring(0, Math.min(40, title.length())).toLowerCase();
                if (seen.add(key)) {
                    BingNewsItem item = new BingNewsItem();
                    item.title = title.length() > 120 ? title.substring(0, 117) + "..." : title;
                    item.url = rssItem.getLink();
                    item.source = sourceName;
                    items.add(item);
                }
            }
        } catch (Exception e) {
            log.warn("Bing News fetch failed for '{}': {}", query, e.getMessage());
        }
        return items;
    }
}
