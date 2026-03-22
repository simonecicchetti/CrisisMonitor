package com.crisismonitor.service;

import com.crisismonitor.config.MonitoredCountries;
import com.crisismonitor.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Generates AI country analysis profiles using Qwen 3.5-Plus with web search.
 * Pre-generated and stored in Firestore for instant access.
 *
 * Migrated from Claude Sonnet to Qwen 3.5-Plus:
 * - 87% cost reduction ($364/yr → $47/yr)
 * - Web search adds real-time grounding (Claude lacks this)
 * - Quality sufficient for structured 5-section country profiles
 *
 * Uses: risk scores, GDELT spikes, nowcast predictions, RSS news, ReliefWeb reports.
 * All from memoryFallback (no live API calls during generation).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CountryAnalysisGenerator {

    private final CacheWarmupService cacheWarmupService;
    private final NowcastService nowcastService;
    private final FirestoreService firestoreService;
    private final ObjectMapper objectMapper;

    @Value("${DASHSCOPE_API_KEY:}")
    private String dashscopeApiKey;

    private static final String MODEL = "qwen3.5-plus";

    private final WebClient qwenClient = WebClient.builder()
            .baseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
            .build();

    /**
     * Generate analysis for a single country.
     * Builds context from all available data sources, then calls Qwen.
     */
    public Map<String, Object> generateCountryAnalysis(String iso3) {
        log.info("Generating AI analysis for {}", iso3);

        StringBuilder ctx = new StringBuilder();
        ctx.append("COUNTRY ANALYSIS: ").append(MonitoredCountries.getName(iso3))
           .append(" (").append(iso3).append(")\n");
        ctx.append("Region: ").append(MonitoredCountries.getRegion(iso3)).append("\n");
        ctx.append("Date: ").append(LocalDate.now()).append("\n\n");

        // Risk scores
        @SuppressWarnings("unchecked")
        List<RiskScore> riskScores = cacheWarmupService.getFallback("allRiskScores");
        if (riskScores != null) {
            riskScores.stream()
                .filter(rs -> iso3.equals(rs.getIso3()))
                .findFirst()
                .ifPresent(rs -> {
                    ctx.append("RISK ASSESSMENT:\n");
                    ctx.append(String.format("  Overall: %d/100 (%s)\n", rs.getScore(), rs.getRiskLevel()));
                    ctx.append(String.format("  Food Security: %d/100\n", rs.getFoodSecurityScore()));
                    ctx.append(String.format("  Conflict: %d/100\n", rs.getConflictScore()));
                    ctx.append(String.format("  Climate: %d/100\n", rs.getClimateScore()));
                    ctx.append(String.format("  Economic: %d/100\n", rs.getEconomicScore()));
                    if (rs.getDrivers() != null)
                        ctx.append("  Primary drivers: ").append(String.join(", ", rs.getDrivers())).append("\n");
                    if (rs.getTrend() != null)
                        ctx.append("  Trend: ").append(rs.getTrend()).append("\n");
                    if (rs.getHorizon() != null)
                        ctx.append("  Horizon: ").append(rs.getHorizon()).append("\n");
                    if (rs.getGdeltZScore() != null)
                        ctx.append(String.format("  Media z-score: %.1f\n", rs.getGdeltZScore()));
                    if (rs.getPrecipitationAnomaly() != null)
                        ctx.append(String.format("  Precipitation anomaly: %+.0f%%\n", rs.getPrecipitationAnomaly()));
                    if (rs.getCurrencyChange30d() != null)
                        ctx.append(String.format("  Currency 30d change: %+.1f%%\n", rs.getCurrencyChange30d()));
                });
        }

        // Nowcast food insecurity prediction
        try {
            var nowcasts = nowcastService.getNowcastAll();
            if (nowcasts != null) {
                nowcasts.stream()
                    .filter(n -> iso3.equals(n.getIso3()))
                    .findFirst()
                    .ifPresent(n -> {
                        ctx.append("\nFOOD INSECURITY NOWCAST (90-day prediction):\n");
                        ctx.append(String.format("  Current proxy: %.1f%%\n", n.getCurrentProxy() != null ? n.getCurrentProxy() : 0));
                        ctx.append(String.format("  Predicted 90d change: %+.1f%%\n", n.getPredictedChange90d() != null ? n.getPredictedChange90d() : 0));
                        ctx.append(String.format("  Projected: %.1f%%\n", n.getProjectedProxy() != null ? n.getProjectedProxy() : 0));
                        ctx.append(String.format("  Trend: %s\n", n.getTrend() != null ? n.getTrend() : "—"));
                        if (n.getFcsPrevalence() != null) ctx.append(String.format("  FCS prevalence: %.1f%%\n", n.getFcsPrevalence()));
                        if (n.getRcsiPrevalence() != null) ctx.append(String.format("  rCSI prevalence: %.1f%%\n", n.getRcsiPrevalence()));
                    });
            }
        } catch (Exception e) { /* skip */ }

        // GDELT media spikes
        @SuppressWarnings("unchecked")
        List<MediaSpike> spikes = cacheWarmupService.getFallback("gdeltAllSpikes");
        if (spikes != null) {
            spikes.stream()
                .filter(s -> iso3.equals(s.getIso3()))
                .findFirst()
                .ifPresent(s -> {
                    ctx.append("\nMEDIA COVERAGE (GDELT):\n");
                    if (s.getZScore() != null) ctx.append(String.format("  z-score: %.1f\n", s.getZScore()));
                    if (s.getArticlesLast7Days() != null) ctx.append(String.format("  Articles (7d): %d\n", s.getArticlesLast7Days()));
                    if (s.getSpikeLevel() != null) ctx.append("  Spike level: ").append(s.getSpikeLevel()).append("\n");
                    if (s.getTopHeadlines() != null) {
                        ctx.append("  Headlines:\n");
                        s.getTopHeadlines().stream().limit(5).forEach(h -> ctx.append("    - ").append(h).append("\n"));
                    }
                });
        }

        // News headlines from RSS + ReliefWeb (cached)
        @SuppressWarnings("unchecked")
        List<Map<String, String>> headlines = cacheWarmupService.getFallback("newsHeadlines");
        if (headlines != null) {
            var countryNews = headlines.stream()
                .filter(h -> iso3.equals(h.get("iso3")))
                .collect(Collectors.toList());
            if (!countryNews.isEmpty()) {
                ctx.append("\nRECENT NEWS & REPORTS:\n");
                countryNews.stream().limit(8).forEach(h ->
                    ctx.append("  [").append(h.getOrDefault("type", "")).append("] ").append(h.getOrDefault("title", "")).append("\n"));
            }
        }

        // Active situations
        try {
            @SuppressWarnings("unchecked")
            var sitReport = (SituationDetectionService.SituationReport) cacheWarmupService.getFallback("activeSituations");
            if (sitReport != null && sitReport.getSituations() != null) {
                var countrySits = sitReport.getSituations().stream()
                    .filter(s -> iso3.equals(s.getIso3()))
                    .collect(Collectors.toList());
                if (!countrySits.isEmpty()) {
                    ctx.append("\nACTIVE SITUATIONS:\n");
                    countrySits.forEach(s -> ctx.append("  - ").append(s.getSituationLabel() != null ? s.getSituationLabel() : "")
                        .append(" (").append(s.getSeverity() != null ? s.getSeverity() : "").append(")\n"));
                }
            }
        } catch (Exception e) { /* skip */ }

        // Call Qwen with web search
        String analysis = callQwen(iso3, ctx.toString());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iso3", iso3);
        result.put("countryName", MonitoredCountries.getName(iso3));
        result.put("region", MonitoredCountries.getRegion(iso3));
        result.put("analysis", analysis);
        result.put("generatedAt", LocalDate.now().toString());
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    private String callQwen(String iso3, String context) {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
            log.warn("DashScope API key not configured for country analysis");
            return null;
        }

        String safeContext = context.replace("\\", "\\\\").replace("\"", "'").replace("\t", " ");
        if (safeContext.length() > 6000) safeContext = safeContext.substring(0, 6000) + "\n[...truncated]";

        String countryName = MonitoredCountries.getName(iso3);

        String prompt = "You are a senior intelligence analyst writing a country risk profile for " + countryName + ".\n\n" +
            "Use the PLATFORM DATA below AND web search to verify current conditions as of " + LocalDate.now() + ".\n\n" +
            "Write a 300-400 word analytical profile.\n\n" +
            "STRUCTURE:\n" +
            "1. Opening: One sentence summary of the country's current crisis status\n" +
            "2. Key Risks: 2-3 paragraphs analyzing the main risk drivers (use the scores as evidence)\n" +
            "3. Food Security Outlook: Use the nowcast prediction to assess trajectory\n" +
            "4. Trajectory: Where is this country heading in the next 30-90 days?\n\n" +
            "RULES:\n" +
            "- Cite specific scores and data points from the platform\n" +
            "- Use web search to verify and supplement with recent events\n" +
            "- Be analytical, not descriptive — explain WHY, not just WHAT\n" +
            "- Countries at war ARE at war. State it directly.\n" +
            "- Do NOT cite agencies by name (WFP, UNHCR, FAO). Describe situations.\n" +
            "- NEVER use internal scores like '78/100' or 'food score 92'. Describe severity: 'extreme food crisis', 'active war'.\n" +
            "- USE real numbers from news: death tolls, displaced people, price changes. NOT index values.\n\n" +
            "PLATFORM DATA:\n" + safeContext;

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", MODEL);
            request.put("max_tokens", 800);
            request.put("enable_search", true);
            request.put("messages", List.of(
                Map.of("role", "system", "content",
                    "You are a crisis correspondent writing in the style of Robert Fisk — direct, unflinching, grounded in specifics. " +
                    "Use web search to verify current conditions. " +
                    "Write analytical prose, not bullet points. " +
                    "Respond with the analysis text only, no JSON, no markdown headers."),
                Map.of("role", "user", "content", prompt)
            ));

            String response = qwenClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + dashscopeApiKey)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))
                .block();

            JsonNode root = objectMapper.readTree(response);
            // OpenAI-compatible format
            if (root.has("choices") && root.path("choices").size() > 0) {
                return root.path("choices").path(0).path("message").path("content").asText();
            }
            // DashScope native format
            JsonNode output = root.path("output");
            if (!output.isMissingNode() && output.has("choices") && output.path("choices").size() > 0) {
                return output.path("choices").path(0).path("message").path("content").asText();
            }
            log.warn("Unexpected Qwen response format for {}", iso3);
            return null;
        } catch (Exception e) {
            log.warn("Qwen country analysis failed for {}: {}", iso3, e.getMessage());
            return null;
        }
    }

    /**
     * Generate and save all country analyses to Firestore.
     * Called from ReportSchedulerService.
     */
    public void generateAllCountryAnalyses() {
        log.info("Generating country analyses for {} countries", MonitoredCountries.CRISIS_COUNTRIES.size());
        int success = 0;

        for (String iso3 : MonitoredCountries.CRISIS_COUNTRIES) {
            try {
                var analysis = generateCountryAnalysis(iso3);
                if (analysis != null && analysis.get("analysis") != null) {
                    firestoreService.saveGeneratedReport("country_" + iso3, analysis);
                    success++;
                    log.info("  Saved country analysis: {}", iso3);
                }
                Thread.sleep(2000); // Rate limit Qwen API
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("  Failed country analysis {}: {}", iso3, e.getMessage());
            }
        }

        log.info("Country analyses complete: {}/{}", success, MonitoredCountries.CRISIS_COUNTRIES.size());
    }

    /**
     * Get pre-generated country analysis from Firestore.
     */
    public Map<String, Object> getPreGeneratedAnalysis(String iso3) {
        return firestoreService.getGeneratedReport("country_" + iso3);
    }
}
