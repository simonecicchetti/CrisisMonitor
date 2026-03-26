package com.crisismonitor.service;

import com.crisismonitor.model.MediaSpike;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Crisis Watch — Event-driven early warning.
 *
 * Monitors GDELT media spikes every 3 hours. When a country shows
 * abnormal media coverage (z-score surge), triggers an AI assessment
 * to determine if a new crisis is developing.
 *
 * This catches events like:
 * - Sudden war outbreak (media explodes before surveys move)
 * - Regime collapse (political coverage precedes food impact)
 * - Natural disaster (satellite/media before ground data)
 *
 * Cost: ~$0.01-0.05 per triggered assessment (only fires for 2-5 countries/day)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrisisWatchService {

    private final GDELTService gdeltService;
    private final QwenScoringService qwenScoringService;
    private final FirestoreService firestoreService;
    private final CacheWarmupService cacheWarmupService;
    private final ObjectMapper objectMapper;

    @Value("${DASHSCOPE_API_KEY:}")
    private String dashscopeApiKey;

    private static final String MODEL = "qwen3.5-plus";

    private final WebClient qwenClient = WebClient.builder()
            .baseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
            .build();

    // Track last known z-scores to detect CHANGES (not just high levels)
    private final Map<String, Double> lastZScores = new HashMap<>();

    // Track which countries were assessed recently (avoid re-triggering)
    private final Map<String, Long> lastAssessed = new HashMap<>();

    // Prevent concurrent scans
    private volatile boolean scanning = false;

    // Minimum hours between crisis assessments for same country
    private static final long COOLDOWN_HOURS = 12;

    // Z-score thresholds
    private static final double TRIGGER_ZSCORE = 3.0;       // Absolute: major media spike
    private static final double TRIGGER_ZSCORE_JUMP = 1.5;  // Relative: sudden increase from last check

    /**
     * Crisis Watch scan. Checks for media spikes that could indicate developing crises.
     * Called manually via POST /api/crisis-watch/scan (admin only).
     * TODO: enable @Scheduled after testing phase
     * @Scheduled(fixedRate = 3 * 60 * 60 * 1000, initialDelay = 4 * 60 * 60 * 1000)
     */
    public void scan() {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) return;
        if (scanning) {
            log.info("Crisis Watch: scan already in progress, skipping");
            return;
        }
        scanning = true;

        try {
        log.info("=== Crisis Watch scan starting ===");

        @SuppressWarnings("unchecked")
        List<MediaSpike> spikes = cacheWarmupService.getFallback("gdeltAllSpikes");
        if (spikes == null || spikes.isEmpty()) {
            log.info("Crisis Watch: no GDELT data available");
            return;
        }

        List<String> triggered = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (MediaSpike spike : spikes) {
            String iso3 = spike.getIso3();
            double zScore = spike.getZScore() != null ? spike.getZScore() : 0;

            // Check cooldown
            Long lastTime = lastAssessed.get(iso3);
            if (lastTime != null && (now - lastTime) < COOLDOWN_HOURS * 3600 * 1000) {
                continue;
            }

            // Check triggers
            Double prevZ = lastZScores.get(iso3);
            double jump = (prevZ != null) ? zScore - prevZ : 0;

            boolean triggerAbsolute = zScore >= TRIGGER_ZSCORE;
            boolean triggerJump = jump >= TRIGGER_ZSCORE_JUMP;

            if (triggerAbsolute || triggerJump) {
                String reason = triggerAbsolute
                        ? String.format("z-score=%.1f (above %.1f threshold)", zScore, TRIGGER_ZSCORE)
                        : String.format("z-score jumped %.1f→%.1f (+%.1f)", prevZ, zScore, jump);
                log.info("Crisis Watch TRIGGERED for {}: {}", iso3, reason);
                triggered.add(iso3);
            }

            // Update tracking
            lastZScores.put(iso3, zScore);
        }

        if (triggered.isEmpty()) {
            log.info("Crisis Watch: no triggers, all stable");
            return;
        }

        log.info("Crisis Watch: {} countries triggered, running assessments", triggered.size());

        for (String iso3 : triggered) {
            try {
                assessCrisisWatch(iso3);
                lastAssessed.put(iso3, now);
                Thread.sleep(3000); // Rate limit
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Crisis Watch assessment failed for {}: {}", iso3, e.getMessage());
            }
        }

        log.info("=== Crisis Watch scan complete: {} assessed ===", triggered.size());
        } finally {
            scanning = false;
        }
    }

    /**
     * Run a focused crisis assessment for a single country.
     * Compares with last known Qwen score to detect significant changes.
     */
    private void assessCrisisWatch(String iso3) {
        // Get last known scores
        QwenScoringService.CountryScore lastScore = qwenScoringService.getScore(iso3);
        String lastSummary = lastScore != null ? lastScore.getSummary() : "No previous assessment";
        String lastDate = lastScore != null ? lastScore.getGeneratedAt() : "never";

        String countryName = com.crisismonitor.config.MonitoredCountries.getName(iso3);
        if (countryName == null) countryName = iso3;

        // Build context
        StringBuilder ctx = new StringBuilder();
        ctx.append("Country: ").append(countryName).append(" (").append(iso3).append(")\n");
        ctx.append("Date: ").append(LocalDate.now()).append("\n");
        ctx.append("ALERT: This country was flagged by our media monitoring system for unusual coverage spike.\n\n");

        // Add current GDELT spike info
        @SuppressWarnings("unchecked")
        List<MediaSpike> spikes = cacheWarmupService.getFallback("gdeltAllSpikes");
        if (spikes != null) {
            spikes.stream().filter(s -> iso3.equals(s.getIso3())).findFirst().ifPresent(s -> {
                ctx.append("MEDIA SPIKE DETECTED:\n");
                if (s.getZScore() != null) ctx.append("  Z-score: ").append(String.format("%.1f", s.getZScore())).append("\n");
                if (s.getArticlesLast7Days() != null) ctx.append("  Articles (7d): ").append(s.getArticlesLast7Days()).append("\n");
                if (s.getTopHeadlines() != null && !s.getTopHeadlines().isEmpty()) {
                    ctx.append("  Recent headlines:\n");
                    s.getTopHeadlines().stream().limit(5).forEach(h -> ctx.append("    - ").append(h).append("\n"));
                }
                ctx.append("\n");
            });
        }

        ctx.append("LAST AI ASSESSMENT (").append(lastDate).append("): ").append(lastSummary).append("\n\n");

        if (lastScore != null) {
            ctx.append(String.format("LAST SCORES: food=%d, conflict=%d, climate=%d, economic=%d\n",
                    lastScore.getFoodScore(), lastScore.getConflictScore(),
                    lastScore.getClimateScore(), lastScore.getEconomicScore()));
        }

        // Crisis Watch prompt — focused on CHANGE detection
        String prompt = """
            You are monitoring %s for a humanitarian crisis early warning system.

            Our media monitoring detected an UNUSUAL SPIKE in news coverage for this country.

            YOUR TASK:
            1. Use web search to find out WHAT IS HAPPENING right now in %s
            2. Determine if this is a NEW crisis or escalation vs normal coverage
            3. Re-score the country if the situation has changed significantly

            CONTEXT FROM OUR SYSTEM:
            %s

            RESPOND IN EXACTLY THIS JSON FORMAT:
            {
              "is_new_crisis": <true/false — is this a NEW development or escalation?>,
              "what_changed": "<1-2 sentences: what specifically happened>",
              "food": <0-100>,
              "food_reason": "<1 sentence>",
              "conflict": <0-100>,
              "conflict_reason": "<1 sentence>",
              "climate": <0-100>,
              "climate_reason": "<1 sentence>",
              "economic": <0-100>,
              "economic_reason": "<1 sentence>",
              "summary": "<1 sentence overall — focus on what CHANGED>",
              "urgency": "<LOW/MEDIUM/HIGH/CRITICAL>"
            }

            SCORING: Same 0-100 scale. If nothing significant changed, keep previous scores.
            If a NEW crisis is developing, score should reflect the EMERGING situation.
            """.formatted(countryName, countryName, ctx.toString());

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", MODEL);
            request.put("max_tokens", 600);
            request.put("enable_search", true);
            request.put("messages", List.of(
                    Map.of("role", "system", "content",
                            "You are a crisis early warning analyst. Use web search to find the latest developments. " +
                            "Focus on what CHANGED in the last 24-48 hours. Today is " + LocalDate.now() + ". " +
                            "Respond with valid JSON only."),
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

            processCrisisWatchResponse(iso3, countryName, response, lastScore);

        } catch (Exception e) {
            log.warn("Crisis Watch AI call failed for {}: {}", iso3, e.getMessage());
        }
    }

    private void processCrisisWatchResponse(String iso3, String countryName,
                                             String response, QwenScoringService.CountryScore lastScore) {
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
                    return;
                }
            }

            int jsonStart = content.indexOf('{');
            int jsonEnd = content.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd < 0) return;

            JsonNode scores = objectMapper.readTree(content.substring(jsonStart, jsonEnd + 1));

            boolean isNewCrisis = scores.path("is_new_crisis").asBoolean(false);
            String whatChanged = scores.path("what_changed").asText("");
            String urgency = scores.path("urgency").asText("LOW");
            int food = clamp(scores.path("food").asInt(0));
            int conflict = clamp(scores.path("conflict").asInt(0));
            int climate = clamp(scores.path("climate").asInt(0));
            int economic = clamp(scores.path("economic").asInt(0));
            String summary = scores.path("summary").asText("");

            // Determine if scores changed significantly
            int maxDelta = 0;
            if (lastScore != null) {
                maxDelta = Math.max(maxDelta, Math.abs(food - lastScore.getFoodScore()));
                maxDelta = Math.max(maxDelta, Math.abs(conflict - lastScore.getConflictScore()));
                maxDelta = Math.max(maxDelta, Math.abs(climate - lastScore.getClimateScore()));
                maxDelta = Math.max(maxDelta, Math.abs(economic - lastScore.getEconomicScore()));
            }

            log.info("Crisis Watch {} [{}]: new_crisis={}, urgency={}, maxDelta={}, what={}",
                    iso3, countryName, isNewCrisis, urgency, maxDelta, whatChanged);

            // Only update Firestore if significant change detected
            if (isNewCrisis || maxDelta >= 10 || "HIGH".equals(urgency) || "CRITICAL".equals(urgency)) {
                log.info("  >>> UPDATING Qwen scores for {} (crisis watch override)", iso3);

                QwenScoringService.CountryScore newScore = new QwenScoringService.CountryScore();
                newScore.setIso3(iso3);
                newScore.setCountryName(countryName);
                newScore.setFoodScore(food);
                newScore.setFoodReason(scores.path("food_reason").asText(""));
                newScore.setConflictScore(conflict);
                newScore.setConflictReason(scores.path("conflict_reason").asText(""));
                newScore.setClimateScore(climate);
                newScore.setClimateReason(scores.path("climate_reason").asText(""));
                newScore.setEconomicScore(economic);
                newScore.setEconomicReason(scores.path("economic_reason").asText(""));
                newScore.setSummary("[Crisis Watch] " + summary);
                newScore.setGeneratedAt(LocalDateTime.now().toString());

                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.convertValue(newScore, Map.class);
                data.put("timestamp", System.currentTimeMillis());
                data.put("crisisWatch", true);
                data.put("whatChanged", whatChanged);
                data.put("urgency", urgency);
                firestoreService.saveDocument("qwenScores", iso3, data);

                log.info("  >>> {} scores updated: food={}, conflict={}, climate={}, economic={}",
                        iso3, food, conflict, climate, economic);
            } else {
                log.info("  No significant change for {} (maxDelta={}, urgency={})", iso3, maxDelta, urgency);
            }

        } catch (Exception e) {
            log.warn("Crisis Watch response parse failed for {}: {}", iso3, e.getMessage());
        }
    }

    private static int clamp(int v) { return Math.max(0, Math.min(100, v)); }
}
