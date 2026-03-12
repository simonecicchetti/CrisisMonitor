package com.crisismonitor.service;

import com.crisismonitor.model.MobilityStock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

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
 * - Layer 3: AI synthesis (Claude)
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
    private final ObjectMapper objectMapper;

    @Value("${anthropic.api.key:}")
    private String apiKey;

    @Value("${anthropic.model:claude-3-haiku-20240307}")
    private String model;

    private final WebClient claudeClient = WebClient.builder()
            .baseUrl("https://api.anthropic.com/v1")
            .build();

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

        // Get region countries - log warning if region not found
        List<String> targetCountries;
        if (region != null && !region.isEmpty()) {
            if (REGION_COUNTRIES.containsKey(region)) {
                targetCountries = REGION_COUNTRIES.get(region);
                log.info("Region '{}' has {} countries", region, targetCountries.size());
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
        int maxReliefWebCountries = 12;  // Increased from 6
        boolean isDataLimited = false;

        // Use cached GDELT spikes instead of making new API calls per country
        // This avoids 15s × N rate-limited calls that cause the report to hang
        try {
            var cachedSpikes = gdeltService.getAllConflictSpikes();
            if (cachedSpikes != null) {
                for (var spike : cachedSpikes) {
                    String iso3 = spike.getIso3();
                    if (!countriesToSearch.contains(iso3)) continue;

                    CountryData cd = countryDataMap.computeIfAbsent(iso3, k -> new CountryData(iso3));
                    cd.mediaCount = spike.getArticlesLast7Days() != null ? spike.getArticlesLast7Days() : 0;

                    // Use cached headline titles from spike data
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
            }
        } catch (Exception e) {
            log.warn("Error reading cached GDELT data: {}", e.getMessage());
        }

        // Fetch ReliefWeb reports with correct timeframe
        for (String iso3 : countriesToSearch.subList(0, Math.min(maxReliefWebCountries, countriesToSearch.size()))) {
            try {
                // Pass days parameter to ReliefWeb
                var reports = reliefWebService.getLatestReports(iso3, 5, days);
                if (reports != null) {
                    CountryData cd = countryDataMap.computeIfAbsent(iso3, k -> new CountryData(iso3));

                    for (var r : reports) {
                        // Check theme OR keyword match
                        boolean themeMatch = matchesReliefWebTheme(r, topic);
                        boolean keywordMatch = matchesTopic(r.getTitle(), keywords);

                        if (themeMatch || keywordMatch) {
                            cd.reportsCount++;
                            cd.reports.add(r.getTitle());

                            allSources.add(new SourceItem(
                                "ReliefWeb",
                                r.getTitle(),
                                r.getUrl(),
                                r.getSource(),
                                r.getDate() != null ? r.getDate() : "Recent"
                            ));
                        }
                    }
                }
                // Small delay to avoid rate limiting
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("Error fetching ReliefWeb for {}: {}", iso3, e.getMessage());
            }
        }

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

        // Overall trend: ONLY if we have flow data, otherwise "signal_only"
        String overallTrend;
        if (!anyFlowDelta) {
            overallTrend = "signal_only";  // Honest: we have signal but no flow data to compute trend
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

        List<KeyDevelopment> keyDevelopments = generateKeyDevelopments(topic, region, countryMatrix, allSources);
        report.setKeyDevelopments(keyDevelopments);

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
     * Generate 3 key development bullets using Claude AI
     */
    private List<KeyDevelopment> generateKeyDevelopments(String topic, String region,
            List<CountryMetrics> countryMatrix, List<SourceItem> sources) {

        List<KeyDevelopment> developments = new ArrayList<>();

        // Build context for Claude
        StringBuilder context = new StringBuilder();
        context.append("Topic: ").append(topic).append("\n");
        context.append("Region: ").append(region != null ? region.toUpperCase() : "Global").append("\n\n");

        context.append("Country data (NOTE: Stock=latest snapshot, NOT movement data. Flow Δ=n/a means no trend data):\n");
        for (CountryMetrics cm : countryMatrix.stream().limit(5).collect(Collectors.toList())) {
            context.append(String.format("- %s: signal=%d (media+reports), stock=%s, flowDelta=%s\n",
                cm.getCountry(), cm.getSignalCount(), cm.getStockData(), cm.getFlowDelta()));
            if (cm.getTopHeadline() != null) {
                context.append("  Headline: ").append(cm.getTopHeadline()).append("\n");
            }
        }

        context.append("\nTop headlines:\n");
        for (SourceItem s : sources.stream().filter(s -> "GDELT".equals(s.getType())).limit(5).collect(Collectors.toList())) {
            context.append("- ").append(s.getTitle()).append("\n");
        }

        context.append("\nOperational reports:\n");
        for (SourceItem s : sources.stream().filter(s -> "ReliefWeb".equals(s.getType())).limit(3).collect(Collectors.toList())) {
            context.append("- ").append(s.getTitle()).append("\n");
        }

        // Call Claude for synthesis
        String synthesis = callClaudeForSynthesis(topic, context.toString());

        if (synthesis != null && !synthesis.isEmpty()) {
            // Parse Claude response into bullets
            String[] lines = synthesis.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("-") || line.startsWith("•") || line.startsWith("1") || line.startsWith("2") || line.startsWith("3")) {
                    line = line.replaceFirst("^[-•123]\\.?\\s*", "").trim();
                    if (!line.isEmpty() && developments.size() < 3) {
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

        return developments;
    }

    /**
     * Call Claude API for synthesis
     */
    private String callClaudeForSynthesis(String topic, String context) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Claude API key not configured, using fallback synthesis");
            return null;
        }

        String prompt = String.format("""
            You are a humanitarian intelligence analyst writing a SIGNAL-ONLY report (limited evidence period).

            CONTEXT: This is a %s report. Flow delta (movement change) is NOT AVAILABLE, so you cannot claim trends.

            YOUR TASK: Write exactly 3 bullets that accurately describe what signal exists.

            FORMAT:
            - Bullet 1: What media signal shows (topic + country + source count)
            - Bullet 2: Operational reporting status (count + specificity note)
            - Bullet 3: Flow/trend status (explain n/a, note stock data if relevant)

            STRICT RULES:
            - Start each bullet with "-"
            - Keep under 90 characters per bullet
            - Cite exact counts: "(2 headlines)", "(1 report)"
            - NO speculation, NO trend claims without flow data
            - If limited: say "Limited signal" not "No data"
            - Sound like an analyst, not an apology

            EXAMPLES OF GOOD BULLETS:
            - "Media signal concentrated on Haiti TPS legal status (2 headlines)"
            - "Operational reporting limited for LAC migration this period (1 ReliefWeb)"
            - "Flow data unavailable; trend not computed. Stock: 1.4M IDPs (DTM latest)"

            EXAMPLES OF BAD BULLETS (don't write these):
            - "Insufficient evidence base for analysis" (too negative)
            - "Migration flows appear to be decreasing" (speculation without flow data)

            DATA:
            %s
            """, topic, context);

        try {
            Map<String, Object> request = Map.of(
                "model", model,
                "max_tokens", 300,
                "messages", List.of(Map.of("role", "user", "content", prompt))
            );

            String response = claudeClient.post()
                .uri("/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            JsonNode root = objectMapper.readTree(response);
            return root.path("content").get(0).path("text").asText();

        } catch (Exception e) {
            log.debug("Claude synthesis failed: {}", e.getMessage());
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
}
