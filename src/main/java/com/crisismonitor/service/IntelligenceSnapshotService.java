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
        // Sections
        private String conflictOutlook;       // What will happen in conflict zones
        private String foodSecurityOutlook;    // Food security trajectory
        private String economicOutlook;       // Economic/market forecast
        private String humanitarianOutlook;   // Humanitarian operations forecast
        private String keyPredictions;        // 5-7 specific, falsifiable predictions
        private String riskEscalations;       // Countries likely to escalate in 14 days
        private String methodology;           // What data sources informed this
    }

    /**
     * Get or generate today's predictive analysis.
     */
    public PredictiveAnalysis getTodayAnalysis(String language) {
        String lang = language != null ? language.toLowerCase().trim() : "en";
        if (lang.isBlank()) lang = "en";
        String docId = "predictive_" + LocalDate.now() + "_" + lang;

        // Check cache
        Map<String, Object> cached = firestoreService.getDocument("predictiveAnalysis", docId);
        if (cached != null) {
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

        String systemPrompt = "You are a senior crisis intelligence analyst producing a daily predictive briefing. " +
            "You have access to comprehensive real-time data from multiple sources. " +
            "Your job is NOT to summarize what happened — it is to predict what WILL happen in the next 7-14 days " +
            "based on the data patterns you observe. Be specific. Be falsifiable. Name countries, timelines, thresholds. " +
            "Write like you're briefing a UN Emergency Relief Coordinator who needs to decide where to pre-position resources TODAY.";

        String prompt = "INTELLIGENCE SNAPSHOT — " + LocalDate.now() + "\n\n" + snapshot + "\n\n" +
            "Based on ALL the data above, produce a predictive intelligence briefing.\n\n" +
            "RESPOND IN JSON (no markdown, no backticks):\n" +
            "{\n" +
            "  \"conflictOutlook\": \"<150-200 words: What will happen in active conflict zones in the next 7-14 days. " +
                "Which fronts will escalate, which will de-escalate, and why the data supports this. Cite specific indicators.>\",\n" +
            "  \"foodSecurityOutlook\": \"<150-200 words: Food security trajectory. Use nowcast predictions + price data. " +
                "Which countries will cross critical thresholds. What supply chain disruptions are building.>\",\n" +
            "  \"economicOutlook\": \"<100-150 words: Currency devaluations, commodity price pressures, and their humanitarian impact. " +
                "Which economies are approaching tipping points.>\",\n" +
            "  \"humanitarianOutlook\": \"<100-150 words: Operational forecast. Where will access shrink. Where will funding gaps force program suspension. " +
                "What displacement flows are building.>\",\n" +
            "  \"keyPredictions\": \"<5-7 specific, dated predictions in bullet format. Each prediction must be falsifiable. " +
                "Example: '• By April 5: Sudan cereal prices will exceed 300% of 5-year average in Khartoum markets.' " +
                "Example: '• Within 10 days: Cuba will experience a third nationwide grid failure.' " +
                "Be bold. Use the data.>\",\n" +
            "  \"riskEscalations\": \"<List of 5-8 countries most likely to see significant risk score increases in the next 14 days, " +
                "with one-line reason each.>\"\n" +
            "}";

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", "qwen3.5-plus");
            request.put("max_tokens", 3000);
            request.put("temperature", 0.3);
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
            analysis.setMethodology("Generated from: " + LocalDate.now() + " platform snapshot — " +
                "risk scores (47 countries), nowcast ML (80 countries), RSS headlines, GDELT media spikes, " +
                "FAO food prices, currency data. Qwen3.5-Plus with web search enabled.");

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
