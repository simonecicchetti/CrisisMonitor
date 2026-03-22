package com.crisismonitor.service;

import com.crisismonitor.model.RiskScore;
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
 * Daily Intelligence Snapshot — aggregates ALL platform data into a structured
 * context that feeds Qwen3.5-Plus for genuine predictive analysis.
 *
 * Not a summary. Not a rewrite of headlines. A data-driven forecast.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntelligenceSnapshotService {

    private final CacheWarmupService cacheWarmupService;
    private final NowcastService nowcastService;
    private final FAOFoodPriceService faoFoodPriceService;
    private final FirestoreService firestoreService;
    private final ObjectMapper objectMapper;

    @Value("${DASHSCOPE_API_KEY:}")
    private String dashscopeApiKey;

    private final WebClient qwenClient = WebClient.builder()
            .baseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();

    @Data
    public static class PredictiveAnalysis {
        private String date;
        private String generatedAt;
        private String language;
        // Reactive sections (what the data shows)
        private String conflictOutlook;
        private String foodSecurityOutlook;
        private String economicOutlook;
        private String humanitarianOutlook;
        private String keyPredictions;
        private String riskEscalations;
        // Proactive sections (what the data doesn't show yet)
        private String emergingThreats;       // Threats building below the radar
        private String cascadingEffects;      // Cross-border spillover chains
        private String methodology;
    }

    /**
     * Force regeneration — deletes cached version and generates fresh.
     */
    public PredictiveAnalysis regenerate(String language) {
        String lang = language != null ? language.toLowerCase().trim() : "en";
        if (lang.isBlank()) lang = "en";
        String docId = "predictive_" + LocalDate.now() + "_" + lang;
        // Overwrite with empty to invalidate, then generate fresh
        firestoreService.saveDocument("predictiveAnalysis", docId, Map.of("invalidated", true));
        log.info("Invalidated predictive analysis cache for {}", docId);
        PredictiveAnalysis analysis = generateAnalysis();
        if (analysis != null) {
            analysis.setLanguage(lang);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.convertValue(analysis, Map.class);
                data.put("timestamp", System.currentTimeMillis());
                firestoreService.saveDocument("predictiveAnalysis", docId, data);
            } catch (Exception e) { log.error("Failed to save: {}", e.getMessage()); }
        }
        return analysis;
    }

    /**
     * Get or generate today's predictive analysis.
     */
    public PredictiveAnalysis getTodayAnalysis(String language) {
        String lang = language != null ? language.toLowerCase().trim() : "en";
        if (lang.isBlank()) lang = "en";
        String docId = "predictive_" + LocalDate.now() + "_" + lang;

        // Check cache (skip if invalidated)
        Map<String, Object> cached = firestoreService.getDocument("predictiveAnalysis", docId);
        if (cached != null && !cached.containsKey("invalidated") && cached.containsKey("conflictOutlook")) {
            return objectMapper.convertValue(cached, PredictiveAnalysis.class);
        }

        // Generate English first
        if (!"en".equals(lang)) {
            PredictiveAnalysis enAnalysis = getTodayAnalysis("en");
            if (enAnalysis == null) return null;
            // TODO: translate via qwen-flash if needed
            return enAnalysis;
        }

        // Build the snapshot and generate
        PredictiveAnalysis analysis = generateAnalysis();
        if (analysis != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.convertValue(analysis, Map.class);
                data.put("timestamp", System.currentTimeMillis());
                firestoreService.saveDocument("predictiveAnalysis", docId, data);
            } catch (Exception e) {
                log.error("Failed to save predictive analysis: {}", e.getMessage());
            }
        }
        return analysis;
    }

    private PredictiveAnalysis generateAnalysis() {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) return null;

        String snapshot = buildSnapshot();
        if (snapshot.length() < 500) {
            log.warn("Snapshot too small ({} chars), skipping analysis", snapshot.length());
            return null;
        }

        log.info("Generating predictive analysis from {} char snapshot", snapshot.length());

        String systemPrompt = "You are a senior crisis intelligence analyst producing a daily PROACTIVE intelligence briefing. " +
            "You have two jobs:\n" +
            "1. REACTIVE: Analyze what the data shows and forecast likely developments in 7-14 days.\n" +
            "2. PROACTIVE: Identify threats that are BUILDING but not yet visible in the data — policy changes, " +
            "political shifts, cross-border spillovers, second-order effects, supply chain vulnerabilities.\n\n" +
            "Think in CAUSAL CHAINS: Policy X in Country A → economic pressure → migration to Country B → " +
            "food insecurity spike → humanitarian crisis. Use web search to find signals the platform data misses.\n\n" +
            "LANGUAGE RULES:\n" +
            "- NEVER use absolute language: 'will happen', 'will ignite'. These are assessments, not facts.\n" +
            "- USE probabilistic language: 'is likely to', 'data suggests', 'high probability of', 'risk of', " +
            "'indicators point toward', 'conditions are building for'.\n" +
            "- Distinguish confidence: 'strong indicators suggest' (high) vs 'early signals point to' (medium) vs 'there is a possibility' (low).\n" +
            "- Tie every assessment to a data point or verifiable signal.\n" +
            "- NEVER mention agency names (FAO, WFP, UNHCR, WHO, IOM). Describe situations and data, not sources.\n" +
            "- NEVER use specific dates in predictions. Use [SHORT-TERM] (1-2 weeks) or [MEDIUM-TERM] (1-3 months).\n" +
            "- Write like you're briefing a decision-maker who allocates humanitarian resources.";

        String prompt = "INTELLIGENCE SNAPSHOT — " + LocalDate.now() + "\n\n" + snapshot + "\n\n" +
            "Produce a PROACTIVE intelligence briefing. Go beyond what the data shows — identify what's BUILDING.\n\n" +
            "RESPOND IN JSON (no markdown, no backticks):\n" +
            "{\n" +
            "  \"conflictOutlook\": \"<150-200 words: Which conflict fronts show indicators of escalation or de-escalation. " +
                "Include second-order effects: how does conflict in Country A affect Country B's stability?>\",\n" +
            "  \"foodSecurityOutlook\": \"<150-200 words: Use nowcast predictions + price data. " +
                "Which countries approach critical thresholds. What supply chain pressures are building. " +
                "How do currency collapses translate to food access failures?>\",\n" +
            "  \"economicOutlook\": \"<100-150 words: Currency devaluations, commodity pressures, and their cascading humanitarian impact. " +
                "Which economies approach tipping points. How do sanctions, trade policy shifts, or energy markets create downstream crises?>\",\n" +
            "  \"humanitarianOutlook\": \"<100-150 words: Where access is likely to shrink. Where funding gaps risk program suspension. " +
                "What displacement flows are building and where will receiving communities face pressure.>\",\n" +
            "  \"emergingThreats\": \"<150-200 words: PROACTIVE SCAN — threats building BELOW the radar. " +
                "Use web search to identify: political shifts (elections, coups, policy changes) that could trigger crises; " +
                "economic pressures not yet reflected in currency data; climate events building (approaching rainy/lean seasons); " +
                "cross-border dynamics (e.g., restrictive migration policy in Country A creating pressure in Country B). " +
                "Think 30-90 days ahead. Name specific countries, policies, timelines.>\",\n" +
            "  \"cascadingEffects\": \"<100-150 words: Map 3-4 CAUSAL CHAINS currently active. Format: " +
                "'[Trigger] → [First-order effect] → [Second-order effect] → [Humanitarian impact]'. " +
                "Example: 'Hormuz blockade → oil price spike → fertilizer cost increase → planting season disruption in East Africa → " +
                "food insecurity surge Q3 2026'. Be specific, cite countries.>\",\n" +
            "  \"keyPredictions\": \"<7-10 predictions in bullet format, each tagged [SHORT-TERM] (1-2 weeks) or [MEDIUM-TERM] (1-3 months). " +
                "Mix reactive (from data) and proactive (from analysis). No specific dates. Use probabilistic language. " +
                "Example: '• [SHORT-TERM] Given SDG 336% devaluation, Khartoum cereal prices are likely to exceed 300% of 5-year average (high confidence).' " +
                "Example: '• [MEDIUM-TERM] Chile migration enforcement policies may trigger increased Peru border crossings (medium confidence).' " +
                "Include at least 2 proactive predictions. NEVER mention agency names. " +
                "CRITICAL: every prediction must be CREDIBLE and PROBABLE — grounded in specific data from the snapshot. " +
                "Do NOT make dramatic predictions for shock value. If data doesn't support a prediction, don't make it. " +
                "A boring but accurate prediction is infinitely more valuable than a dramatic but baseless one.>\",\n" +
            "  \"riskEscalations\": \"<List of 5-8 countries most likely to see significant deterioration in 14 days, " +
                "with one-line data-backed reason. Include at least 1-2 countries not currently flagged as high-risk but showing early warning signs.>\"\n" +
            "}";

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", "qwen3.5-plus");
            request.put("max_tokens", 4500);
            request.put("temperature", 0.4);
            request.put("enable_search", true);
            request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", prompt)
            ));

            String response = qwenClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + dashscopeApiKey)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(120))
                .block();

            return parseAnalysis(response);
        } catch (Exception e) {
            log.error("Predictive analysis generation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build the intelligence snapshot — ALL platform data in one structured text.
     */
    private String buildSnapshot() {
        StringBuilder sb = new StringBuilder();

        // 1. Risk Scores — top 25 countries
        @SuppressWarnings("unchecked")
        List<RiskScore> scores = cacheWarmupService.getFallback("allRiskScores");
        if (scores != null && !scores.isEmpty()) {
            sb.append("=== RISK SCORES (top 25 by severity) ===\n");
            scores.stream().limit(25).forEach(s -> {
                sb.append(s.getCountryName()).append(" (").append(s.getIso3()).append("): ")
                  .append(s.getScore()).append("/100 [").append(s.getRiskLevel()).append("]");
                sb.append(" food=").append(s.getFoodSecurityScore())
                  .append(" conflict=").append(s.getConflictScore())
                  .append(" climate=").append(s.getClimateScore())
                  .append(" economic=").append(s.getEconomicScore());
                if (s.getDrivers() != null && !s.getDrivers().isEmpty()) {
                    sb.append(" drivers=[").append(String.join(",", s.getDrivers())).append("]");
                }
                if (s.getSummary() != null && !s.getSummary().isBlank()) {
                    sb.append(" | ").append(s.getSummary());
                }
                sb.append("\n");
            });
            sb.append("\n");
        }

        // 2. Nowcast ML Predictions — worsening countries
        try {
            var predictions = nowcastService.getNowcastAll();
            if (predictions != null && !predictions.isEmpty()) {
                sb.append("=== NOWCAST ML PREDICTIONS (90-day food insecurity forecast) ===\n");
                var worsening = predictions.stream()
                    .filter(p -> p.getPredictedChange90d() != null && p.getPredictedChange90d() > 2)
                    .sorted((a, b) -> Double.compare(b.getPredictedChange90d(), a.getPredictedChange90d()))
                    .limit(15)
                    .collect(Collectors.toList());
                sb.append("WORSENING (").append(worsening.size()).append(" countries):\n");
                worsening.forEach(p -> sb.append("  ").append(p.getCountryName())
                    .append(": current=").append(String.format("%.1f%%", p.getCurrentProxy()))
                    .append(" → projected=").append(String.format("%.1f%%", p.getProjectedProxy()))
                    .append(" (").append(String.format("%+.1fpp", p.getPredictedChange90d())).append(")\n"));

                var improving = predictions.stream()
                    .filter(p -> p.getPredictedChange90d() != null && p.getPredictedChange90d() < -5)
                    .sorted((a, b) -> Double.compare(a.getPredictedChange90d(), b.getPredictedChange90d()))
                    .limit(10)
                    .collect(Collectors.toList());
                sb.append("IMPROVING (").append(improving.size()).append(" notable):\n");
                improving.forEach(p -> sb.append("  ").append(p.getCountryName())
                    .append(": ").append(String.format("%+.1fpp", p.getPredictedChange90d())).append("\n"));
                sb.append("\n");
            }
        } catch (Exception e) { log.debug("Nowcast data unavailable: {}", e.getMessage()); }

        // 3. FAO Food Price Indices
        try {
            var fao = faoFoodPriceService.getLatest();
            if (fao != null) {
                sb.append("=== FAO FOOD PRICE INDEX ===\n");
                sb.append("Food=").append(String.format("%.1f", fao.getFoodIndex()));
                sb.append(" Cereals=").append(String.format("%.1f", fao.getCerealsIndex()));
                sb.append(" Oils=").append(String.format("%.1f", fao.getOilsIndex()));
                sb.append(" Dairy=").append(String.format("%.1f", fao.getDairyIndex()));
                sb.append(" Meat=").append(String.format("%.1f", fao.getMeatIndex()));
                sb.append(" Sugar=").append(String.format("%.1f", fao.getSugarIndex()));
                sb.append("\n\n");
            }
        } catch (Exception e) { /* skip */ }

        // 4. RSS Headlines — latest 30
        @SuppressWarnings("unchecked")
        List<Map<String, String>> headlines = cacheWarmupService.getFallback("newsHeadlines");
        if (headlines != null && !headlines.isEmpty()) {
            sb.append("=== TODAY'S NEWS (latest ").append(Math.min(30, headlines.size())).append(") ===\n");
            headlines.stream().limit(30).forEach(h -> {
                String source = h.getOrDefault("source", "");
                String title = h.getOrDefault("title", "");
                String country = h.getOrDefault("country", "");
                String type = h.getOrDefault("type", "");
                sb.append("  [").append(source).append("]");
                if (!country.isBlank()) sb.append(" ").append(country);
                if (!type.isBlank()) sb.append(" (").append(type).append(")");
                sb.append(": ").append(title).append("\n");
            });
            sb.append("\n");
        }

        // 5. GDELT Media Spikes
        @SuppressWarnings("unchecked")
        List<Object> spikes = cacheWarmupService.getFallback("gdeltAllSpikes");
        if (spikes != null && !spikes.isEmpty()) {
            sb.append("=== GDELT MEDIA SPIKES (conflict attention anomalies) ===\n");
            // Spikes are MediaSpike objects
            spikes.stream().limit(10).forEach(s -> sb.append("  ").append(s.toString()).append("\n"));
            sb.append("\n");
        }

        // 6. Currency stress
        @SuppressWarnings("unchecked")
        List<Object> currencies = cacheWarmupService.getFallback("allCurrencyData");
        if (currencies != null && !currencies.isEmpty()) {
            sb.append("=== CURRENCY STRESS (30-day devaluation vs USD) ===\n");
            currencies.stream().limit(15).forEach(c -> sb.append("  ").append(c.toString()).append("\n"));
            sb.append("\n");
        }

        sb.append("=== END OF SNAPSHOT ===\nDate: ").append(LocalDate.now()).append("\n");
        return sb.toString();
    }

    private PredictiveAnalysis parseAnalysis(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            String content;
            if (root.has("choices") && root.path("choices").size() > 0) {
                content = root.path("choices").path(0).path("message").path("content").asText();
            } else {
                JsonNode output = root.path("output");
                if (!output.isMissingNode() && output.has("choices")) {
                    content = output.path("choices").path(0).path("message").path("content").asText();
                } else {
                    log.warn("Unexpected response format for predictive analysis");
                    return null;
                }
            }

            int jsonStart = content.indexOf('{');
            int jsonEnd = content.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd < 0) {
                log.warn("No JSON in predictive analysis response");
                return null;
            }

            JsonNode json = objectMapper.readTree(content.substring(jsonStart, jsonEnd + 1));

            PredictiveAnalysis analysis = new PredictiveAnalysis();
            analysis.setDate(LocalDate.now().toString());
            analysis.setGeneratedAt(java.time.Instant.now().toString());
            analysis.setLanguage("en");
            analysis.setConflictOutlook(json.path("conflictOutlook").asText(""));
            analysis.setFoodSecurityOutlook(json.path("foodSecurityOutlook").asText(""));
            analysis.setEconomicOutlook(json.path("economicOutlook").asText(""));
            analysis.setHumanitarianOutlook(json.path("humanitarianOutlook").asText(""));
            analysis.setKeyPredictions(json.path("keyPredictions").asText(""));
            analysis.setRiskEscalations(json.path("riskEscalations").asText(""));
            analysis.setEmergingThreats(json.path("emergingThreats").asText(""));
            analysis.setCascadingEffects(json.path("cascadingEffects").asText(""));
            analysis.setMethodology("Generated from " + LocalDate.now() + " platform snapshot: " +
                "risk scores (47 countries), nowcast ML predictions (80 countries), live news feeds, " +
                "conflict media analysis, food price indices, and currency data.");

            if (analysis.getConflictOutlook().isBlank() && analysis.getKeyPredictions().isBlank()) {
                log.warn("Analysis appears empty");
                return null;
            }

            log.info("Predictive analysis generated: {} chars total",
                (analysis.getConflictOutlook() + analysis.getFoodSecurityOutlook() +
                 analysis.getKeyPredictions()).length());
            return analysis;
        } catch (Exception e) {
            log.error("Failed to parse predictive analysis: {}", e.getMessage());
            return null;
        }
    }
}
