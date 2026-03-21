package com.crisismonitor.service;

import com.crisismonitor.config.MonitoredCountries;
import com.crisismonitor.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI-powered country risk scoring using Qwen3.5-Plus with web search.
 *
 * Replaces formula-based scoring with AI assessment.
 * Each country gets 4 scores (food, conflict, climate, economic) with explanations.
 * Web search grounds the analysis in real-time events.
 *
 * Runs weekly via cron. Results stored in Firestore.
 * Cost: ~$0.50/week for 47 countries.
 *
 * >>> CHANGE POINT: switch to separate DashScope API key for crisis-monitor
 * >>> CHANGE POINT: add more scoring dimensions (health, political stability)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QwenScoringService {

    private final CacheWarmupService cacheWarmupService;
    private final NowcastService nowcastService;
    private final FAOFoodPriceService faoFoodPriceService;
    private final FirestoreService firestoreService;
    private final ObjectMapper objectMapper;

    @Value("${DASHSCOPE_API_KEY:}")
    private String dashscopeApiKey;

    private static final String MODEL = "qwen3.5-plus";

    // OpenAI-compatible endpoint (supports all features including web search)
    private final WebClient qwenClient = WebClient.builder()
            .baseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
            .build();

    @Data
    public static class CountryScore {
        private String iso3;
        private String countryName;
        private int foodScore;
        private String foodReason;
        private int conflictScore;
        private String conflictReason;
        private int climateScore;
        private String climateReason;
        private int economicScore;
        private String economicReason;
        private int overallScore;
        private String riskLevel;
        private List<String> drivers;
        private String summary;
        private String generatedAt;
    }

    /**
     * Score a single country using Qwen with web search.
     */
    public CountryScore scoreCountry(String iso3) {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
            log.warn("DashScope API key not configured");
            return null;
        }

        String countryName = MonitoredCountries.getName(iso3);
        log.info("Qwen scoring: {} ({})", countryName, iso3);

        // Build context from platform data
        StringBuilder ctx = new StringBuilder();
        ctx.append("Country: ").append(countryName).append(" (").append(iso3).append(")\n");
        ctx.append("Date: ").append(LocalDate.now()).append("\n\n");

        // Platform data (same sources as before)
        addPlatformData(ctx, iso3);

        String prompt = buildScoringPrompt(countryName, ctx.toString());

        try {
            // OpenAI-compatible format with web search enabled
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", MODEL);
            request.put("max_tokens", 800);
            request.put("enable_search", true);  // Web search for real-time grounding
            request.put("messages", List.of(
                Map.of("role", "system", "content",
                    "You are a humanitarian crisis risk assessment AI. " +
                    "Use web search to verify CURRENT conditions as of today " + LocalDate.now() + ". " +
                    "Always respond with valid JSON only, no markdown."),
                Map.of("role", "user", "content", prompt)
            ));

            String response = qwenClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + dashscopeApiKey)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(90))
                .block();

            return parseScores(iso3, countryName, response);

        } catch (Exception e) {
            log.warn("Qwen scoring failed for {}: {}", iso3, e.getMessage());
            return null;
        }
    }

    // Verified armed conflicts — mirrors RiskScoreService.CONFLICT_BASELINE
    // Each entry: description + minimum score the AI must assign
    private record ConflictInfo(String description, int minScore) {}
    private static final Map<String, ConflictInfo> VERIFIED_CONFLICTS = Map.ofEntries(
        Map.entry("PSE", new ConflictInfo("Active war: Israeli military operations in Gaza, siege, ground invasion", 80)),
        Map.entry("IRN", new ConflictInfo("Active war: US/Israel-Iran military conflict since Feb 2026, Hormuz blockade, ongoing airstrikes", 78)),
        Map.entry("UKR", new ConflictInfo("Active war: Russia-Ukraine full-scale invasion since Feb 2022", 75)),
        Map.entry("SDN", new ConflictInfo("Civil war: SAF vs RSF, 150K+ estimated dead, widespread displacement", 70)),
        Map.entry("ISR", new ConflictInfo("Multi-front conflict: Gaza operations + Iran war", 70)),
        Map.entry("LBN", new ConflictInfo("Post-2024 war, fragile ceasefire, border tensions", 65)),
        Map.entry("MMR", new ConflictInfo("Nationwide civil war: resistance forces vs military junta", 60)),
        Map.entry("SYR", new ConflictInfo("Multi-front civil war, post-Assad transition instability", 60)),
        Map.entry("YEM", new ConflictInfo("Houthi conflict + US strikes + Red Sea/Hormuz maritime operations", 60)),
        Map.entry("BFA", new ConflictInfo("Armed groups (JNIM/IS) besieging 40+ towns, population displacement", 55)),
        Map.entry("ETH", new ConflictInfo("Amhara insurgency (Fano militia), ongoing armed clashes", 55)),
        Map.entry("COD", new ConflictInfo("M23 + ADF armed group operations, eastern DRC instability", 55)),
        Map.entry("HTI", new ConflictInfo("Armed gangs control 80-90% of Port-au-Prince", 53)),
        Map.entry("SOM", new ConflictInfo("Al-Shabaab insurgency, ongoing military operations", 50)),
        Map.entry("SSD", new ConflictInfo("Inter-communal armed violence, fragile peace", 50)),
        Map.entry("MLI", new ConflictInfo("JNIM armed group advance, military operations", 50))
    );

    private void addPlatformData(StringBuilder ctx, String iso3) {
        // Verified conflict info — CRITICAL: this is ground truth
        ConflictInfo verifiedConflict = VERIFIED_CONFLICTS.get(iso3);
        if (verifiedConflict != null) {
            ctx.append("⚠ VERIFIED ARMED CONFLICT (confirmed by analysts):\n");
            ctx.append("  ").append(verifiedConflict.description()).append("\n");
            ctx.append("  You MUST reflect this in the conflict score. Score CANNOT be below ")
               .append(verifiedConflict.minScore()).append(".\n\n");
        }

        // Risk scores (current formula-based)
        @SuppressWarnings("unchecked")
        List<RiskScore> riskScores = cacheWarmupService.getFallback("allRiskScores");
        if (riskScores != null) {
            riskScores.stream().filter(rs -> iso3.equals(rs.getIso3())).findFirst().ifPresent(rs -> {
                ctx.append("CURRENT PLATFORM SCORES (formula-based, for reference):\n");
                ctx.append(String.format("  Overall: %d, Food: %d, Conflict: %d, Climate: %d, Economic: %d\n",
                    rs.getScore(), rs.getFoodSecurityScore(), rs.getConflictScore(), rs.getClimateScore(), rs.getEconomicScore()));
                if (rs.getGdeltZScore() != null) ctx.append(String.format("  GDELT media z-score: %.1f\n", rs.getGdeltZScore()));
                if (rs.getPrecipitationAnomaly() != null) ctx.append(String.format("  Precipitation anomaly: %+.0f%%\n", rs.getPrecipitationAnomaly()));
                if (rs.getCurrencyChange30d() != null) ctx.append(String.format("  Currency 30d change: %+.1f%%\n", rs.getCurrencyChange30d()));
            });
        }

        // Nowcast
        try {
            var nowcasts = nowcastService.getNowcastAll();
            if (nowcasts != null) {
                nowcasts.stream().filter(n -> iso3.equals(n.getIso3())).findFirst().ifPresent(n -> {
                    ctx.append(String.format("\nFOOD NOWCAST: current=%.1f%%, predicted90d=%+.1f%%, trend=%s\n",
                        n.getCurrentProxy() != null ? n.getCurrentProxy() : 0,
                        n.getPredictedChange90d() != null ? n.getPredictedChange90d() : 0,
                        n.getTrend() != null ? n.getTrend() : "—"));
                });
            }
        } catch (Exception e) { /* skip */ }

        // FAO
        try {
            var fao = faoFoodPriceService.getLatest();
            if (fao != null) {
                ctx.append(String.format("\nGLOBAL FOOD PRICES (FAO): Food=%.1f, Cereals=%.1f (%s)\n",
                    fao.getFoodIndex(), fao.getCerealsIndex(), fao.getDate()));
            }
        } catch (Exception e) { /* skip */ }

        // WFP live food security data
        try {
            var fcsMetrics = cacheWarmupService.getFallback("foodSecurityMetrics");
            if (fcsMetrics instanceof List) {
                @SuppressWarnings("unchecked")
                List<FoodSecurityMetrics> metrics = (List<FoodSecurityMetrics>) fcsMetrics;
                metrics.stream().filter(m -> iso3.equals(m.getIso3())).findFirst().ifPresent(m -> {
                    ctx.append("\nWFP LIVE DATA:\n");
                    if (m.getFcsPrevalence() != null) ctx.append(String.format("  FCS (poor food consumption): %.1f%%\n", m.getFcsPrevalence() * 100));
                    if (m.getRcsiPrevalence() != null) ctx.append(String.format("  rCSI (crisis coping): %.1f%%\n", m.getRcsiPrevalence() * 100));
                });
            }
        } catch (Exception e) { /* skip */ }

        // WFP Severity tier
        try {
            var severityData = cacheWarmupService.getFallback("severityData");
            // Severity is a Map<String, Integer> but might not be in fallback
        } catch (Exception e) { /* skip */ }

        // World Bank Severe Food Insecurity
        try {
            @SuppressWarnings("unchecked")
            Map<String, Double> wbSfi = cacheWarmupService.getFallback("worldBankSFI");
            if (wbSfi != null && wbSfi.containsKey(iso3)) {
                ctx.append(String.format("  World Bank severe food insecurity: %.1f%% of population\n", wbSfi.get(iso3)));
            }
        } catch (Exception e) { /* skip */ }

        // GDELT headlines for this country
        @SuppressWarnings("unchecked")
        List<MediaSpike> spikes = cacheWarmupService.getFallback("gdeltAllSpikes");
        if (spikes != null) {
            spikes.stream().filter(s -> iso3.equals(s.getIso3())).findFirst().ifPresent(s -> {
                ctx.append("\nMEDIA COVERAGE (GDELT):\n");
                if (s.getZScore() != null) ctx.append(String.format("  Media z-score: %.1f\n", s.getZScore()));
                if (s.getArticlesLast7Days() != null) ctx.append(String.format("  Articles (7d): %d\n", s.getArticlesLast7Days()));
                if (s.getTopHeadlines() != null && !s.getTopHeadlines().isEmpty()) {
                    ctx.append("  Top headlines:\n");
                    s.getTopHeadlines().stream().limit(5).forEach(h -> ctx.append("    - ").append(h).append("\n"));
                }
            });
        }

        // News headlines (RSS + ReliefWeb from warmup)
        @SuppressWarnings("unchecked")
        List<Map<String, String>> headlines = cacheWarmupService.getFallback("newsHeadlines");
        if (headlines != null) {
            // Country-specific headlines
            var countryNews = headlines.stream().filter(h -> iso3.equals(h.get("iso3"))).collect(Collectors.toList());

            // Also include regional headlines that mention the country name
            String countryName2 = MonitoredCountries.getName(iso3).toLowerCase();
            var regionalNews = headlines.stream()
                .filter(h -> {
                    String title = (h.getOrDefault("title", "")).toLowerCase();
                    return title.contains(countryName2) && !iso3.equals(h.get("iso3"));
                })
                .collect(Collectors.toList());

            var allNews = new java.util.ArrayList<>(countryNews);
            allNews.addAll(regionalNews);

            if (!allNews.isEmpty()) {
                ctx.append("\nRECENT NEWS & REPORTS:\n");
                allNews.stream().limit(10).forEach(h ->
                    ctx.append("  [").append(h.getOrDefault("type", "")).append("] ").append(h.getOrDefault("title", "")).append("\n"));
            }
        }

        // Active situations
        try {
            @SuppressWarnings("unchecked")
            var sitReport = (SituationDetectionService.SituationReport) cacheWarmupService.getFallback("activeSituations");
            if (sitReport != null && sitReport.getSituations() != null) {
                sitReport.getSituations().stream()
                    .filter(s -> iso3.equals(s.getIso3()))
                    .findFirst()
                    .ifPresent(s -> ctx.append("\nACTIVE SITUATION: ").append(s.getSituationLabel() != null ? s.getSituationLabel() : "").append(" (").append(s.getSeverity() != null ? s.getSeverity() : "").append(")\n"));
            }
        } catch (Exception e) { /* skip */ }

        // WHO disease outbreaks
        try {
            @SuppressWarnings("unchecked")
            var outbreaks = (List<WHODiseaseOutbreakService.DiseaseOutbreak>) cacheWarmupService.getFallback("whoDiseaseOutbreaks");
            if (outbreaks != null) {
                var countryOutbreaks = outbreaks.stream()
                    .filter(o -> iso3.equals(o.getCountryIso3()))
                    .collect(Collectors.toList());
                if (!countryOutbreaks.isEmpty()) {
                    ctx.append("\nWHO DISEASE OUTBREAKS:\n");
                    countryOutbreaks.stream().limit(3).forEach(o ->
                        ctx.append("  - ").append(o.getTitle() != null ? o.getTitle() : "").append("\n"));
                }
            }
        } catch (Exception e) { /* skip */ }
    }

    private String buildScoringPrompt(String countryName, String context) {
        return """
            You are a risk assessment AI for a humanitarian crisis monitoring platform.

            TASK: Assess %s and assign 4 risk scores (0-100) with brief explanations.

            You have TWO sources of information:
            1. PLATFORM DATA below — real-time monitoring data from our system (GDELT media, WFP food surveys, RSS news, ReliefWeb reports, WHO outbreaks, nowcast predictions). TRUST THIS DATA — it is current.
            2. WEB SEARCH — use to verify and supplement. If web search returns no results for a topic, RELY ON PLATFORM DATA.

            USE WEB SEARCH to check:
            - Latest developments for this country (last 7 days)
            - Is there active armed conflict? (for conflict score)
            - Current economic conditions and crises

            IMPORTANT: If our PLATFORM DATA shows news headlines about a crisis (e.g., blackouts, famine, conflict), score accordingly even if web search doesn't confirm — our RSS feeds are more current.

            PLATFORM DATA (real-time, current as of today):
            %s

            RESPOND IN EXACTLY THIS JSON FORMAT (no markdown, no backticks):
            {
              "food": <0-100>,
              "food_reason": "<1 sentence explaining the score>",
              "conflict": <0-100>,
              "conflict_reason": "<1 sentence — ONLY score high for ARMED conflict, NOT political tension>",
              "climate": <0-100>,
              "climate_reason": "<1 sentence>",
              "economic": <0-100>,
              "economic_reason": "<1 sentence>",
              "summary": "<1 sentence overall assessment>"
            }

            SCORING GUIDE:
            - 80-100: Catastrophic / Active crisis (famine, war, economic collapse)
            - 60-79: Severe / Emergency conditions
            - 40-59: High risk / Deteriorating
            - 20-39: Moderate risk / Elevated concern
            - 0-19: Low risk / Stable

            CRITICAL RULES:
            - CONFLICT includes ALL armed violence: international wars, civil wars, military operations, armed groups, airstrikes, drone attacks, insurgency, gang warfare. A country being bombed or conducting military operations has HIGH conflict.
            - CONFLICT is NOT just internal civil war. International wars, cross-border strikes, and proxy conflicts ALL count.
            - CLIMATE means drought, flooding, cyclones actively impacting population. NOT normal seasonal variation.
            - In reason text: describe the SITUATION, not the data source. Do NOT cite specific agencies (WFP, UNHCR, WHO) by name.
            - In reason text: do NOT reference internal scores or index values. Say "severe food crisis" not "food score 85/100".
            - Countries without armed violence but with political repression or sanctions: conflict should be LOW (under 20).
            """.formatted(countryName, context);
    }

    private CountryScore parseScores(String iso3, String countryName, String response) {
        try {
            JsonNode root = objectMapper.readTree(response);

            // DashScope native format: output.choices[0].message.content
            // OpenAI-compatible format: choices[0].message.content
            String content;
            JsonNode output = root.path("output");
            if (!output.isMissingNode() && output.has("choices") && output.path("choices").size() > 0) {
                content = output.path("choices").path(0).path("message").path("content").asText();
            } else if (root.has("choices") && root.path("choices").size() > 0) {
                content = root.path("choices").path(0).path("message").path("content").asText();
            } else {
                log.warn("Unexpected Qwen response format for {}: {}", iso3, response.substring(0, Math.min(300, response.length())));
                return null;
            }

            // Extract JSON from response (may have text around it)
            int jsonStart = content.indexOf('{');
            int jsonEnd = content.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd < 0) {
                log.warn("No JSON found in Qwen response for {}", iso3);
                return null;
            }
            String json = content.substring(jsonStart, jsonEnd + 1);
            JsonNode scores = objectMapper.readTree(json);

            CountryScore cs = new CountryScore();
            cs.setIso3(iso3);
            cs.setCountryName(countryName);
            cs.setFoodScore(clamp(scores.path("food").asInt(0)));
            cs.setFoodReason(scores.path("food_reason").asText(""));
            cs.setConflictScore(clamp(scores.path("conflict").asInt(0)));
            cs.setConflictReason(scores.path("conflict_reason").asText(""));
            cs.setClimateScore(clamp(scores.path("climate").asInt(0)));
            cs.setClimateReason(scores.path("climate_reason").asText(""));
            cs.setEconomicScore(clamp(scores.path("economic").asInt(0)));
            cs.setEconomicReason(scores.path("economic_reason").asText(""));
            cs.setSummary(scores.path("summary").asText(""));
            cs.setGeneratedAt(LocalDate.now().toString());

            // Calculate overall using adaptive power mean.
            // Standard weights: conflict=35%, food=35%, climate=15%, economic=15%
            // BUT: if conflict=0 (no armed conflict), redistribute that weight to food+economic.
            // This prevents countries like Cuba (econ crisis, no war) from being dragged down.
            double p = 1.5;
            double wConflict = 0.35, wFood = 0.35, wClimate = 0.15, wEcon = 0.15;
            if (cs.getConflictScore() <= 5) {
                // No conflict: redistribute to food (50%) and economic (50%)
                wFood += wConflict * 0.5;    // 0.35 + 0.175 = 0.525
                wEcon += wConflict * 0.5;    // 0.15 + 0.175 = 0.325
                wConflict = 0;
            }
            double powerSum = wConflict * Math.pow(Math.max(1, cs.getConflictScore()), p)
                            + wFood * Math.pow(Math.max(1, cs.getFoodScore()), p)
                            + wClimate * Math.pow(Math.max(1, cs.getClimateScore()), p)
                            + wEcon * Math.pow(Math.max(1, cs.getEconomicScore()), p);
            cs.setOverallScore((int) Math.pow(powerSum, 1.0 / p));

            // Risk level
            int score = cs.getOverallScore();
            cs.setRiskLevel(score >= 60 ? "CRITICAL" : score >= 48 ? "ALERT" :
                           score >= 38 ? "WARNING" : score >= 22 ? "WATCH" : "STABLE");

            // Drivers
            List<String> drivers = new ArrayList<>();
            Map<String, Integer> driverScores = Map.of(
                "Food Security", cs.getFoodScore(),
                "Conflict", cs.getConflictScore(),
                "Climate", cs.getClimateScore(),
                "Economic", cs.getEconomicScore());
            driverScores.entrySet().stream()
                .filter(e -> e.getValue() >= 30)
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(3)
                .forEach(e -> drivers.add(e.getKey()));
            cs.setDrivers(drivers);

            log.info("  Qwen scored {}: overall={}, food={}, conflict={}, climate={}, econ={}",
                iso3, cs.getOverallScore(), cs.getFoodScore(), cs.getConflictScore(),
                cs.getClimateScore(), cs.getEconomicScore());
            return cs;

        } catch (Exception e) {
            log.warn("Failed to parse Qwen response for {}: {}", iso3, e.getMessage());
            return null;
        }
    }

    /**
     * Score all monitored countries and save to Firestore.
     */
    public void scoreAllCountries() {
        log.info("=== Starting Qwen country scoring for {} countries ===", MonitoredCountries.CRISIS_COUNTRIES.size());
        int success = 0;

        for (String iso3 : MonitoredCountries.CRISIS_COUNTRIES) {
            try {
                CountryScore score = scoreCountry(iso3);
                if (score != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = objectMapper.convertValue(score, Map.class);
                    data.put("timestamp", System.currentTimeMillis());
                    firestoreService.saveDocument("qwenScores", iso3, data);
                    success++;
                }
                Thread.sleep(2000); // Rate limit
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("  Failed {}: {}", iso3, e.getMessage());
            }
        }
        log.info("=== Qwen scoring complete: {}/{} ===", success, MonitoredCountries.CRISIS_COUNTRIES.size());
    }

    /**
     * Get pre-generated Qwen score for a country.
     */
    public CountryScore getScore(String iso3) {
        Map<String, Object> data = firestoreService.getDocument("qwenScores", iso3);
        if (data == null) return null;
        return objectMapper.convertValue(data, CountryScore.class);
    }

    private static int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
