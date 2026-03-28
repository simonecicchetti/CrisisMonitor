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
 * Daily editorial brief — one AI-generated analytical briefing per day.
 *
 * Structure (Simone's format):
 * 1. Headline (12-16 words, strong analytical statement)
 * 2. Main note (160-220 words, 2 paragraphs: what changed + what it means)
 * 3. Watch today (3 bullets: country / situation)
 *
 * Generated via POST /api/daily-brief/generate (triggered by Cloud Scheduler or manually).
 * NOT via @Scheduled — Cloud Run min-instances=0 means JVM-based crons don't fire reliably.
 *
 * Stored in Firestore: dailyBriefs/{date} (e.g., "2026-03-21")
 * Supports multi-language via `language` parameter.
 * Cost: ~$0.002/day. Uses qwen3.5-plus for generation, qwen-flash for translations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyBriefService {

    private final CacheWarmupService cacheWarmupService;
    private final FAOFoodPriceService faoFoodPriceService;
    private final NowcastService nowcastService;
    private final FirestoreService firestoreService;
    private final ObjectMapper objectMapper;

    @Value("${DASHSCOPE_API_KEY:}")
    private String dashscopeApiKey;

    private final WebClient qwenClient = WebClient.builder()
            .baseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .build();

    // Supported languages
    public static final Map<String, String> SUPPORTED_LANGUAGES = Map.of(
        "en", "English",
        "it", "Italian",
        "fr", "French",
        "es", "Spanish",
        "zh", "Chinese (Simplified)",
        "ar", "Arabic"
    );

    @Data
    public static class DailyBrief {
        private String date;
        private String headline;
        private String paragraph1;
        private String paragraph2;
        private List<WatchItem> watchItems;
        private String generatedAt;
        private String language;
    }

    @Data
    public static class WatchItem {
        private String country;
        private String situation;
    }

    /**
     * Generate and save the daily brief. Idempotent — skips if today's brief already exists.
     * Called by Cloud Scheduler or POST endpoint, NOT by @Scheduled.
     */
    public DailyBrief generateAndSave(String language) {
        return generateAndSave(language, false);
    }

    public DailyBrief generateAndSave(String language, boolean force) {
        String lang = resolveLanguage(language);
        String docId = LocalDate.now() + "_" + lang;

        // Idempotent: skip if already generated today (unless force=true)
        if (!force) {
            Map<String, Object> existing = firestoreService.getDocument("dailyBriefs", docId);
            if (existing != null) {
                log.info("Daily Brief already exists for {}", docId);
                return objectMapper.convertValue(existing, DailyBrief.class);
            }
        }

        DailyBrief brief;
        if ("en".equals(lang)) {
            brief = generateEnglish();
        } else {
            DailyBrief enBrief = generateAndSave("en", force);
            if (enBrief == null) return null;
            brief = translateBrief(enBrief, lang);
        }

        if (brief != null) {
            saveBrief(brief, docId);
            log.info("Daily Brief [{}]: {}", lang, brief.getHeadline());
        }
        return brief;
    }

    /**
     * Generate the English brief from scratch (full analysis).
     */
    private DailyBrief generateEnglish() {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
            log.warn("DashScope API key not configured");
            return null;
        }

        String context = buildContext();
        String prompt = buildPrompt(context);

        String content = callQwen(FISK_STYLE, prompt, 1024, true);
        JsonNode json = extractJson(content);
        if (json == null) return null;

        DailyBrief brief = new DailyBrief();
        brief.setDate(LocalDate.now().toString());
        brief.setHeadline(json.path("headline").asText(""));
        brief.setParagraph1(json.path("paragraph1").asText(""));
        brief.setParagraph2(json.path("paragraph2").asText(""));
        brief.setGeneratedAt(java.time.Instant.now().toString());
        brief.setLanguage("en");

        List<WatchItem> items = new ArrayList<>();
        JsonNode watchArr = json.path("watch");
        if (watchArr.isArray()) {
            int count = 0;
            for (JsonNode w : watchArr) {
                if (count >= 3) break;
                String country = w.path("country").asText("");
                String situation = w.path("situation").asText("");
                if (!country.isBlank() && !situation.isBlank()) {
                    WatchItem item = new WatchItem();
                    item.setCountry(country);
                    item.setSituation(situation);
                    items.add(item);
                    count++;
                }
            }
        }
        brief.setWatchItems(items);
        if (brief.getHeadline().isBlank()) return null;
        return brief;
    }

    private String buildContext() {
        StringBuilder ctx = new StringBuilder();
        ctx.append("Date: ").append(LocalDate.now()).append("\n\n");

        // Top risk scores with enough detail for analytical writing
        @SuppressWarnings("unchecked")
        List<RiskScore> scores = cacheWarmupService.getFallback("allRiskScores");
        if (scores != null) {
            List<RiskScore> sorted = scores.stream()
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());

            ctx.append("TOP RISK COUNTRIES (today):\n");
            sorted.stream().limit(15).forEach(s -> {
                ctx.append("  ").append(s.getCountryName()).append(" (").append(s.getIso3()).append("): ")
                   .append(s.getScore()).append("/100 [").append(s.getRiskLevel()).append("]");
                ctx.append(" food=").append(s.getFoodSecurityScore())
                   .append(" conflict=").append(s.getConflictScore())
                   .append(" climate=").append(s.getClimateScore())
                   .append(" econ=").append(s.getEconomicScore());
                if (s.getDrivers() != null && !s.getDrivers().isEmpty()) {
                    ctx.append(" drivers: ").append(String.join(", ", s.getDrivers()));
                }
                if (s.getSummary() != null && !s.getSummary().isBlank()) {
                    ctx.append(" — ").append(s.getSummary());
                }
                ctx.append("\n");
            });

            // Global counts
            long critical = scores.stream().filter(s -> "CRITICAL".equals(s.getRiskLevel())).count();
            long alert = scores.stream().filter(s -> "ALERT".equals(s.getRiskLevel())).count();
            ctx.append("\nGLOBAL SNAPSHOT: ").append(critical).append(" CRITICAL, ")
               .append(alert).append(" ALERT, ").append(scores.size()).append(" total monitored\n");

            // Identify highest component scores across all countries
            OptionalInt maxFood = scores.stream().mapToInt(RiskScore::getFoodSecurityScore).max();
            OptionalInt maxConflict = scores.stream().mapToInt(RiskScore::getConflictScore).max();
            if (maxFood.isPresent()) ctx.append("Highest food insecurity: ").append(maxFood.getAsInt()).append("/100\n");
            if (maxConflict.isPresent()) ctx.append("Highest conflict intensity: ").append(maxConflict.getAsInt()).append("/100\n");
        }

        // FAO food prices — economic context
        try {
            var fao = faoFoodPriceService.getLatest();
            if (fao != null) {
                ctx.append("\nGLOBAL FOOD PRICES (FAO, ").append(fao.getDate()).append("):\n");
                ctx.append("  Food Index: ").append(String.format("%.1f", fao.getFoodIndex()));
                ctx.append(", Cereals: ").append(String.format("%.1f", fao.getCerealsIndex()));
                ctx.append(", Oils: ").append(String.format("%.1f", fao.getOilsIndex())).append("\n");
            }
        } catch (Exception e) { /* skip */ }

        // Nowcast predictions — forward-looking ML data for credible forecast
        try {
            var predictions = nowcastService.getNowcastAll();
            if (predictions != null && !predictions.isEmpty()) {
                var sorted = predictions.stream()
                    .sorted((a, b) -> Double.compare(
                        b.getPredictedChange90d() != null ? b.getPredictedChange90d() : 0,
                        a.getPredictedChange90d() != null ? a.getPredictedChange90d() : 0))
                    .collect(Collectors.toList());

                ctx.append("\nFOOD INSECURITY FORECAST (ML model, 90-day predictions):\n");
                ctx.append("  Worsening:\n");
                sorted.stream().filter(p -> p.getPredictedChange90d() != null && p.getPredictedChange90d() > 3).limit(5)
                    .forEach(p -> ctx.append("    ").append(p.getCountryName())
                        .append(": current ").append(String.format("%.0f%%", p.getCurrentProxy()))
                        .append(" → projected ").append(String.format("%.0f%%", p.getProjectedProxy()))
                        .append(" (").append(String.format("%+.1fpp", p.getPredictedChange90d())).append(")\n"));
                ctx.append("  Improving:\n");
                sorted.stream().filter(p -> p.getPredictedChange90d() != null && p.getPredictedChange90d() < -3).limit(3)
                    .forEach(p -> ctx.append("    ").append(p.getCountryName())
                        .append(": ").append(String.format("%+.1fpp", p.getPredictedChange90d())).append("\n"));
            }
        } catch (Exception e) { /* skip */ }

        // Currency devaluations — economic crisis signals
        if (scores != null) {
            var currencyShocks = scores.stream()
                .filter(s -> s.getCurrencyChange30d() != null && Math.abs(s.getCurrencyChange30d()) > 5)
                .sorted((a, b) -> Double.compare(
                    b.getCurrencyChange30d() != null ? b.getCurrencyChange30d() : 0,
                    a.getCurrencyChange30d() != null ? a.getCurrencyChange30d() : 0))
                .limit(5)
                .collect(Collectors.toList());
            if (!currencyShocks.isEmpty()) {
                ctx.append("\nCURRENCY MOVEMENTS (30-day):\n");
                currencyShocks.forEach(s -> ctx.append("  ").append(s.getCountryName())
                    .append(": ").append(String.format("%+.1f%%", s.getCurrencyChange30d())).append("\n"));
            }
        }

        // News headlines — the raw material for the editorial
        @SuppressWarnings("unchecked")
        List<Map<String, String>> headlines = cacheWarmupService.getFallback("newsHeadlines");
        if (headlines != null && !headlines.isEmpty()) {
            ctx.append("\nLATEST NEWS HEADLINES (RSS + ReliefWeb):\n");
            headlines.stream().limit(25).forEach(h ->
                ctx.append("  [").append(h.getOrDefault("type", "")).append("] ")
                   .append(h.getOrDefault("title", ""))
                   .append(" (").append(h.getOrDefault("source", "")).append(")\n"));
        }

        // GDACS disaster alerts
        @SuppressWarnings("unchecked")
        List<GDACSService.DisasterAlert> gdacsAlerts = cacheWarmupService.getFallback("gdacsAlerts");
        if (gdacsAlerts != null && !gdacsAlerts.isEmpty()) {
            ctx.append("\nACTIVE NATURAL DISASTERS:\n");
            gdacsAlerts.stream().limit(10).forEach(a ->
                ctx.append("  [").append(a.getAlertLevel()).append("] ").append(a.getTitle()).append("\n"));
        }

        // Verified conflicts — single source of truth
        ctx.append("\nVERIFIED ARMED CONFLICTS (analyst-confirmed):\n");
        com.crisismonitor.config.VerifiedConflicts.CONFLICTS.forEach((iso, desc) ->
            ctx.append("  ").append(com.crisismonitor.config.MonitoredCountries.getName(iso))
               .append(": ").append(desc).append("\n"));

        return ctx.toString();
    }

    private String buildPrompt(String context) {
        return """
            You are the editorial voice of Notamy News, a global risk intelligence platform. Your audience: humanitarian directors, crisis coordinators, government advisors. They have 30 seconds to read this.

            Write today's Daily Brief. This is NOT a news summary — it is an analytical editorial. Think: opening paragraph of an Economist leader, or a Council on Foreign Relations daily brief.

            YOUR APPROACH:
            - Lead with the THESIS: what is the single most important thing about today's global crisis picture?
            - Connect crises: if food prices are high AND conflict is intense in the same region, that's a story
            - Second-order effects: what does the Iran war mean for Red Sea shipping, which means for food supply to East Africa?
            - Be prescriptive for Watch items: not "Iran / conflict risk" but "Iran / Hormuz blockade impact on Gulf food imports"
            - Countries at war ARE at war. Not "showing elevated indicators."

            PLATFORM DATA:
            """ + context + """

            RESPOND IN EXACTLY THIS JSON FORMAT (no markdown, no backticks):
            {
              "headline": "<12-16 word analytical thesis about today's global risk picture>",
              "paragraph1": "<80-110 words: what is happening RIGHT NOW. Name countries, describe situations concretely.>",
              "paragraph2": "<80-110 words: SO WHAT for humanitarian operations. Connect dots between crises.>",
              "watch": [
                {"country": "<country>", "situation": "<6-10 words: specific operational concern>"},
                {"country": "<country>", "situation": "<6-10 words: specific operational concern>"},
                {"country": "<country>", "situation": "<6-10 words: specific operational concern>"}
              ]
            }

            QUALITY BAR:
            - Every sentence must carry new information. Zero filler.
            - No remains to be seen, situation continues, international community must act.
            - Specific over general. Sudan RSF advance on El Fasher over conflict in Sudan.

            CRITICAL — NUMBER USAGE:
            - NEVER use internal platform scores like 92/100 or 78/100 or food score 80. These are internal metrics meaningless to readers.
            - DESCRIBE the situation instead: mass famine, catastrophic food crisis, severe economic collapse, active bombardment.
            - DO use real-world numbers: death tolls, displaced populations, funding gaps.
            - DO NOT cite raw index values. Say food prices surging, not 174 FAO points.
            - DO NOT attribute data to agencies (WFP, FAO, UNHCR). Present as your analysis.
            """;
    }

    /**
     * Translate an English brief to another language. Preserves structure, translates content.
     */
    private DailyBrief translateBrief(DailyBrief enBrief, String targetLang) {
        String langName = SUPPORTED_LANGUAGES.getOrDefault(targetLang, targetLang);
        log.info("Translating Daily Brief to {} ({})", langName, targetLang);

        // Build the English content as JSON for translation
        StringBuilder source = new StringBuilder();
        source.append("headline: ").append(enBrief.getHeadline()).append("\n");
        source.append("paragraph1: ").append(enBrief.getParagraph1()).append("\n");
        source.append("paragraph2: ").append(enBrief.getParagraph2()).append("\n");
        if (enBrief.getWatchItems() != null) {
            for (int i = 0; i < enBrief.getWatchItems().size(); i++) {
                WatchItem w = enBrief.getWatchItems().get(i);
                source.append("watch").append(i + 1).append("_country: ").append(w.getCountry()).append("\n");
                source.append("watch").append(i + 1).append("_situation: ").append(w.getSituation()).append("\n");
            }
        }

        String prompt = "Translate the following editorial brief from English to " + langName + ".\n\n" +
            "RULES:\n" +
            "- Maintain the analytical tone and density. Do not simplify.\n" +
            "- Country names: use the standard name in " + langName + " (e.g., 'Soudan' in French, 'Sudán' in Spanish).\n" +
            "- Keep the same structure. Do not add or remove information.\n" +
            "- Numbers, percentages, and scores stay as-is.\n\n" +
            "SOURCE TEXT:\n" + source + "\n" +
            "RESPOND IN EXACTLY THIS JSON FORMAT (no markdown, no backticks):\n" +
            "{\n" +
            "  \"headline\": \"<translated headline>\",\n" +
            "  \"paragraph1\": \"<translated paragraph1>\",\n" +
            "  \"paragraph2\": \"<translated paragraph2>\",\n" +
            "  \"watch\": [\n" +
            "    {\"country\": \"<country name in " + langName + ">\", \"situation\": \"<translated situation>\"},\n" +
            "    {\"country\": \"<country name in " + langName + ">\", \"situation\": \"<translated situation>\"},\n" +
            "    {\"country\": \"<country name in " + langName + ">\", \"situation\": \"<translated situation>\"}\n" +
            "  ]\n" +
            "}";

        String content = callQwenFlash(prompt, 1024);
        JsonNode json = extractJson(content);
        if (json == null) return null;

        DailyBrief translated = new DailyBrief();
        translated.setDate(enBrief.getDate());
        translated.setHeadline(json.path("headline").asText(""));
        translated.setParagraph1(json.path("paragraph1").asText(""));
        translated.setParagraph2(json.path("paragraph2").asText(""));
        translated.setGeneratedAt(java.time.Instant.now().toString());
        translated.setLanguage(targetLang);

        List<WatchItem> items = new ArrayList<>();
        JsonNode watchArr = json.path("watch");
        if (watchArr.isArray()) {
            for (JsonNode w : watchArr) {
                if (items.size() >= 3) break;
                WatchItem item = new WatchItem();
                item.setCountry(w.path("country").asText(""));
                item.setSituation(w.path("situation").asText(""));
                if (!item.getCountry().isBlank()) items.add(item);
            }
        }
        translated.setWatchItems(items);
        return translated.getHeadline().isBlank() ? null : translated;
    }

    private DailyBrief parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentArr = root.path("content");
            if (!contentArr.isArray() || contentArr.isEmpty()) {
                log.warn("AI response has no content array");
                return null;
            }
            String content = contentArr.get(0).path("text").asText("");
            if (content.isBlank()) {
                log.warn("AI response text is empty");
                return null;
            }

            // Extract JSON
            int jsonStart = content.indexOf('{');
            int jsonEnd = content.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                log.warn("No valid JSON in daily brief response");
                return null;
            }
            String jsonStr = content.substring(jsonStart, jsonEnd + 1);
            JsonNode json = objectMapper.readTree(jsonStr);

            // Validate required fields
            String headline = json.path("headline").asText("");
            String p1 = json.path("paragraph1").asText("");
            String p2 = json.path("paragraph2").asText("");
            if (headline.isBlank() || p1.isBlank()) {
                log.warn("Daily brief has empty headline or paragraph1");
                return null;
            }

            DailyBrief brief = new DailyBrief();
            brief.setDate(LocalDate.now().toString());
            brief.setHeadline(headline);
            brief.setParagraph1(p1);
            brief.setParagraph2(p2);
            brief.setGeneratedAt(java.time.Instant.now().toString());

            List<WatchItem> items = new ArrayList<>();
            JsonNode watchArr = json.path("watch");
            if (watchArr.isArray()) {
                int count = 0;
                for (JsonNode w : watchArr) {
                    if (count >= 3) break;
                    String country = w.path("country").asText("");
                    String situation = w.path("situation").asText("");
                    if (!country.isBlank() && !situation.isBlank()) {
                        WatchItem item = new WatchItem();
                        item.setCountry(country);
                        item.setSituation(situation);
                        items.add(item);
                        count++;
                    }
                }
            }
            brief.setWatchItems(items);

            return brief;
        } catch (Exception e) {
            log.error("Failed to parse daily brief: {}", e.getMessage());
            return null;
        }
    }

    private void saveBrief(DailyBrief brief, String docId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.convertValue(brief, Map.class);
            data.put("timestamp", System.currentTimeMillis());
            firestoreService.saveDocument("dailyBriefs", docId, data);
        } catch (Exception e) {
            log.error("Failed to save daily brief: {}", e.getMessage());
        }
    }

    /**
     * Get today's brief for a language. Returns null if not yet generated.
     * If the requested language doesn't exist but English does, translates on the fly.
     */
    public DailyBrief getTodayBrief(String language) {
        String lang = resolveLanguage(language);
        String docId = LocalDate.now() + "_" + lang;
        Map<String, Object> data = firestoreService.getDocument("dailyBriefs", docId);
        if (data != null) {
            return objectMapper.convertValue(data, DailyBrief.class);
        }
        // Translate from English on the fly if English brief exists
        if (!"en".equals(lang)) {
            String enDocId = LocalDate.now() + "_en";
            data = firestoreService.getDocument("dailyBriefs", enDocId);
            if (data != null) {
                DailyBrief enBrief = objectMapper.convertValue(data, DailyBrief.class);
                log.info("Translating Daily Brief on the fly: en → {}", lang);
                DailyBrief translated = translateBrief(enBrief, lang);
                if (translated != null) {
                    saveBrief(translated, docId);
                    return translated;
                }
                log.warn("Translation to {} failed, returning English fallback", lang);
                return enBrief;
            }
        }
        return null;
    }

    // ==========================================
    // COUNTRY INTELLIGENCE BRIEF — full multi-dimensional analysis
    // ==========================================

    @Data
    public static class CountryBrief {
        private String iso3;
        private String countryName;
        private int overallScore;
        private String riskLevel;
        private int conflictScore;
        private int foodScore;
        private int climateScore;
        private int economicScore;
        private String security;      // Political & Security situation
        private String economy;       // Economic analysis
        private String foodSecurity;  // Food security + nowcast
        private String displacement;  // Migration & displacement
        private String outlook;       // 90-day forecast
        private String language;
        private String generatedAt;
    }

    /**
     * Get or generate a Country Intelligence Brief. On-demand with 24h cache.
     */
    public CountryBrief getCountryBrief(String iso3, String language) {
        String lang = resolveLanguage(language);
        String docId = iso3.toUpperCase() + "_" + LocalDate.now() + "_" + lang;

        // Cache check
        Map<String, Object> cached = firestoreService.getDocument("countryBriefs", docId);
        if (cached != null) {
            return objectMapper.convertValue(cached, CountryBrief.class);
        }

        // Generate English first, then translate if needed
        CountryBrief brief;
        if ("en".equals(lang)) {
            brief = generateCountryBrief(iso3.toUpperCase());
        } else {
            String enDocId = iso3.toUpperCase() + "_" + LocalDate.now() + "_en";
            Map<String, Object> enCached = firestoreService.getDocument("countryBriefs", enDocId);
            CountryBrief enBrief;
            if (enCached != null) {
                enBrief = objectMapper.convertValue(enCached, CountryBrief.class);
            } else {
                enBrief = generateCountryBrief(iso3.toUpperCase());
                if (enBrief != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> enData = objectMapper.convertValue(enBrief, Map.class);
                    enData.put("timestamp", System.currentTimeMillis());
                    firestoreService.saveDocument("countryBriefs", enDocId, enData);
                }
            }
            if (enBrief == null) return null;
            brief = translateCountryBrief(enBrief, lang);
        }

        if (brief != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.convertValue(brief, Map.class);
                data.put("timestamp", System.currentTimeMillis());
                firestoreService.saveDocument("countryBriefs", docId, data);
            } catch (Exception e) {
                log.error("Failed to save country brief: {}", e.getMessage());
            }
        }
        return brief;
    }

    private CountryBrief generateCountryBrief(String iso3) {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) return null;

        // Gather all platform data for this country
        StringBuilder ctx = new StringBuilder();
        String countryName = com.crisismonitor.config.MonitoredCountries.getName(iso3);
        ctx.append("Country: ").append(countryName).append(" (").append(iso3).append(")\n");
        ctx.append("Date: ").append(LocalDate.now()).append("\n\n");

        // Risk scores
        @SuppressWarnings("unchecked")
        List<RiskScore> scores = cacheWarmupService.getFallback("allRiskScores");
        if (scores != null) {
            scores.stream().filter(s -> iso3.equals(s.getIso3())).findFirst().ifPresent(s -> {
                ctx.append("RISK PROFILE: overall=").append(s.getScore()).append("/100 [").append(s.getRiskLevel()).append("]\n");
                ctx.append("  Conflict: ").append(s.getConflictScore()).append("/100");
                if (s.getConflictReason() != null) ctx.append(" — ").append(s.getConflictReason());
                ctx.append("\n  Food: ").append(s.getFoodSecurityScore()).append("/100");
                if (s.getFoodReason() != null) ctx.append(" — ").append(s.getFoodReason());
                ctx.append("\n  Climate: ").append(s.getClimateScore()).append("/100");
                if (s.getClimateReason() != null) ctx.append(" — ").append(s.getClimateReason());
                ctx.append("\n  Economic: ").append(s.getEconomicScore()).append("/100");
                if (s.getEconomicReason() != null) ctx.append(" — ").append(s.getEconomicReason());
                if (s.getSummary() != null) ctx.append("\n  Summary: ").append(s.getSummary());
                ctx.append("\n\n");
            });
        }

        // Nowcast
        try {
            var predictions = nowcastService.getNowcastAll();
            if (predictions != null) {
                predictions.stream().filter(p -> iso3.equals(p.getIso3())).findFirst().ifPresent(p -> {
                    ctx.append("FOOD NOWCAST (90-day prediction):\n");
                    ctx.append("  Current proxy: ").append(String.format("%.1f%%", p.getCurrentProxy()));
                    ctx.append(", Predicted change: ").append(String.format("%+.1fpp", p.getPredictedChange90d()));
                    ctx.append(", Projected: ").append(String.format("%.1f%%", p.getProjectedProxy()));
                    ctx.append(", Trend: ").append(p.getTrend()).append("\n\n");
                });
            }
        } catch (Exception e) { /* skip */ }

        // News headlines
        @SuppressWarnings("unchecked")
        List<Map<String, String>> headlines = cacheWarmupService.getFallback("newsHeadlines");
        if (headlines != null) {
            String nameLower = countryName != null ? countryName.toLowerCase() : "";
            var news = headlines.stream()
                .filter(h -> {
                    String title = h.getOrDefault("title", "").toLowerCase();
                    String hIso3 = h.getOrDefault("iso3", "");
                    return iso3.equals(hIso3) || title.contains(nameLower);
                })
                .limit(10)
                .collect(Collectors.toList());
            if (!news.isEmpty()) {
                ctx.append("RECENT NEWS:\n");
                news.forEach(h -> ctx.append("  [").append(h.getOrDefault("type", ""))
                    .append("] ").append(h.getOrDefault("title", "")).append("\n"));
                ctx.append("\n");
            }
        }

        // Verified conflicts — single source of truth
        String conflict = com.crisismonitor.config.VerifiedConflicts.getDescription(iso3);
        if (conflict != null) {
            ctx.append("VERIFIED CONFLICT: ").append(conflict).append("\n\n");
        }

        String prompt = "You are a senior intelligence analyst writing a Country Intelligence Brief for " + countryName + ".\n\n" +
            "PLATFORM DATA:\n" + ctx + "\n" +
            "Write 5 sections. Be a top analyst: explain WHY the situation is what it is, not just WHAT it is.\n\n" +
            "RESPOND IN JSON (no markdown, no backticks):\n" +
            "{\n" +
            "  \"security\": \"<3-4 sentences: current security/governance reality. If at war, describe fronts, intensity, civilian impact. " +
                "If no conflict, explain what keeps stability or what threatens it. Explain WHY the conflict score is justified.>\",\n" +
            "  \"economy\": \"<3-4 sentences: economic conditions with concrete data — currency movement, inflation, purchasing power. " +
                "Explain what is driving economic stress or stability. Connect to humanitarian impact.>\",\n" +
            "  \"foodSecurity\": \"<3-4 sentences: food insecurity reality. Use nowcast data if available (predicted trajectory). " +
                "Describe access barriers, price pressures, supply chain status. Explain why food score is at this level.>\",\n" +
            "  \"displacement\": \"<2-3 sentences: displacement situation, cross-border flows, internal movement, humanitarian access constraints.>\",\n" +
            "  \"outlook\": \"<3-4 sentences: 90-day probabilistic forecast. Use 'likely', 'indicators suggest', 'risk of'. " +
                "What could improve, what could worsen, and what specific triggers to watch. Tie to data.>\"\n" +
            "}\n\n" +
            "RULES:\n" +
            "- Countries at war ARE at war. State it directly with specifics.\n" +
            "- NEVER output internal scores like '78/100'. Instead DESCRIBE the situation concretely.\n" +
            "- USE real-world numbers: death tolls, displaced people, price changes, percentage points.\n" +
            "- Each section must EXPLAIN why the situation warrants its severity level — a reader should understand the score after reading.\n" +
            "- The outlook MUST use probabilistic language. Never say 'will happen'. Say 'is likely to', 'data suggests', 'high risk of'.\n" +
            "- Validate: if news headlines contradict the score (e.g., blackout crisis but low score), flag the discrepancy.\n" +
            "- Write as if briefing someone who must decide TODAY where to send resources.";

        try {
            String rawContent = callQwen(FISK_STYLE, prompt, 1500, true);
            JsonNode json = extractJson(rawContent);
            if (json == null) return null;

            CountryBrief brief = new CountryBrief();
            brief.setIso3(iso3);
            brief.setCountryName(countryName);
            brief.setSecurity(json.path("security").asText(""));
            brief.setEconomy(json.path("economy").asText(""));
            brief.setFoodSecurity(json.path("foodSecurity").asText(""));
            brief.setDisplacement(json.path("displacement").asText(""));
            brief.setOutlook(json.path("outlook").asText(""));
            brief.setLanguage("en");
            brief.setGeneratedAt(java.time.Instant.now().toString());

            // Fill scores from risk data
            if (scores != null) {
                scores.stream().filter(s -> iso3.equals(s.getIso3())).findFirst().ifPresent(s -> {
                    brief.setOverallScore(s.getScore());
                    brief.setRiskLevel(s.getRiskLevel());
                    brief.setConflictScore(s.getConflictScore());
                    brief.setFoodScore(s.getFoodSecurityScore());
                    brief.setClimateScore(s.getClimateScore());
                    brief.setEconomicScore(s.getEconomicScore());
                });
            }

            if (brief.getSecurity().isBlank()) return null;
            log.info("Country brief generated: {} ({})", countryName, iso3);
            return brief;

        } catch (Exception e) {
            log.error("Country brief generation failed for {}: {}", iso3, e.getMessage());
            return null;
        }
    }

    private CountryBrief translateCountryBrief(CountryBrief en, String targetLang) {
        String langName = SUPPORTED_LANGUAGES.getOrDefault(targetLang, targetLang);
        String source = "security: " + en.getSecurity() + "\n" +
            "economy: " + en.getEconomy() + "\n" +
            "foodSecurity: " + en.getFoodSecurity() + "\n" +
            "displacement: " + en.getDisplacement() + "\n" +
            "outlook: " + en.getOutlook();

        String prompt = "Translate this intelligence brief from English to " + langName + ".\n" +
            "Keep analytical tone, numbers as-is, country names in " + langName + ".\n\n" +
            source + "\n\nRESPOND IN JSON (no markdown):\n" +
            "{\"security\":\"...\",\"economy\":\"...\",\"foodSecurity\":\"...\",\"displacement\":\"...\",\"outlook\":\"...\"}";

        try {
            String rawContent = callQwenFlash(prompt, 1200);
            JsonNode json = extractJson(rawContent);
            if (json == null) return null;

            CountryBrief brief = new CountryBrief();
            brief.setIso3(en.getIso3());
            brief.setCountryName(en.getCountryName());
            brief.setOverallScore(en.getOverallScore());
            brief.setRiskLevel(en.getRiskLevel());
            brief.setConflictScore(en.getConflictScore());
            brief.setFoodScore(en.getFoodScore());
            brief.setClimateScore(en.getClimateScore());
            brief.setEconomicScore(en.getEconomicScore());
            brief.setSecurity(json.path("security").asText(""));
            brief.setEconomy(json.path("economy").asText(""));
            brief.setFoodSecurity(json.path("foodSecurity").asText(""));
            brief.setDisplacement(json.path("displacement").asText(""));
            brief.setOutlook(json.path("outlook").asText(""));
            brief.setLanguage(targetLang);
            brief.setGeneratedAt(java.time.Instant.now().toString());
            return brief;
        } catch (Exception e) {
            log.error("Country brief translation to {} failed: {}", targetLang, e.getMessage());
            return null;
        }
    }

    // ==========================================
    // DEEP DIVE — Watch item expansion
    // ==========================================

    @Data
    public static class DeepDive {
        private String country;
        private String situation;
        private String headline;
        private String paragraph1;
        private String paragraph2;
        private String language;
        private String generatedAt;
    }

    /**
     * Generate a deep-dive on a Watch item. Cached per country+date+lang.
     */
    public DeepDive getOrGenerateDeepDive(String country, String situation, String language) {
        String lang = resolveLanguage(language);
        String safeCountry = country.replaceAll("[^a-zA-Z0-9 ]", "").replace(" ", "_").toLowerCase();
        String docId = LocalDate.now() + "_" + safeCountry + "_" + lang;

        // Check cache
        Map<String, Object> cached = firestoreService.getDocument("dailyBriefDeepDives", docId);
        if (cached != null) {
            return objectMapper.convertValue(cached, DeepDive.class);
        }

        // Generate: English from scratch, others by translation
        DeepDive dive;
        if ("en".equals(lang)) {
            dive = generateDeepDive(country, situation);
        } else {
            // Get/generate English first, then translate
            String enDocId = LocalDate.now() + "_" + safeCountry + "_en";
            Map<String, Object> enCached = firestoreService.getDocument("dailyBriefDeepDives", enDocId);
            DeepDive enDive;
            if (enCached != null) {
                enDive = objectMapper.convertValue(enCached, DeepDive.class);
            } else {
                enDive = generateDeepDive(country, situation);
                if (enDive != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> enData = objectMapper.convertValue(enDive, Map.class);
                    enData.put("timestamp", System.currentTimeMillis());
                    firestoreService.saveDocument("dailyBriefDeepDives", enDocId, enData);
                }
            }
            if (enDive == null) return null;
            dive = translateDeepDive(enDive, lang);
        }

        if (dive != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.convertValue(dive, Map.class);
                data.put("timestamp", System.currentTimeMillis());
                firestoreService.saveDocument("dailyBriefDeepDives", docId, data);
            } catch (Exception e) {
                log.error("Failed to save deep dive: {}", e.getMessage());
            }
        }
        return dive;
    }

    private DeepDive generateDeepDive(String country, String situation) {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) return null;

        // Build focused context for this country
        StringBuilder ctx = new StringBuilder();
        ctx.append("Date: ").append(LocalDate.now()).append("\n");
        ctx.append("Focus: ").append(country).append(" — ").append(situation).append("\n\n");

        @SuppressWarnings("unchecked")
        List<RiskScore> scores = cacheWarmupService.getFallback("allRiskScores");
        if (scores != null) {
            scores.stream()
                .filter(s -> s.getCountryName().equalsIgnoreCase(country)
                    || country.toLowerCase().contains(s.getCountryName().toLowerCase()))
                .findFirst()
                .ifPresent(s -> {
                    ctx.append("RISK PROFILE:\n");
                    ctx.append("  Overall: ").append(s.getScore()).append("/100 [").append(s.getRiskLevel()).append("]\n");
                    ctx.append("  Food: ").append(s.getFoodSecurityScore())
                       .append(", Conflict: ").append(s.getConflictScore())
                       .append(", Climate: ").append(s.getClimateScore())
                       .append(", Economic: ").append(s.getEconomicScore()).append("\n");
                    if (s.getFoodReason() != null) ctx.append("  Food: ").append(s.getFoodReason()).append("\n");
                    if (s.getConflictReason() != null) ctx.append("  Conflict: ").append(s.getConflictReason()).append("\n");
                    if (s.getClimateReason() != null) ctx.append("  Climate: ").append(s.getClimateReason()).append("\n");
                    if (s.getEconomicReason() != null) ctx.append("  Economic: ").append(s.getEconomicReason()).append("\n");
                    if (s.getSummary() != null) ctx.append("  Summary: ").append(s.getSummary()).append("\n");
                });
        }

        // News about this country — resolve name to ISO3 for proper matching
        @SuppressWarnings("unchecked")
        List<Map<String, String>> headlines = cacheWarmupService.getFallback("newsHeadlines");
        if (headlines != null) {
            String countryLower = country.toLowerCase();
            // Try to resolve country name to ISO3 for headline matching
            String resolvedIso3 = com.crisismonitor.config.MonitoredCountries.CRISIS_COUNTRIES.stream()
                .filter(iso -> {
                    String name = com.crisismonitor.config.MonitoredCountries.getName(iso);
                    return name != null && name.equalsIgnoreCase(country);
                })
                .findFirst().orElse(null);

            var countryNews = headlines.stream()
                .filter(h -> {
                    String title = h.getOrDefault("title", "").toLowerCase();
                    String hIso3 = h.getOrDefault("iso3", "");
                    return title.contains(countryLower)
                        || (resolvedIso3 != null && resolvedIso3.equals(hIso3));
                })
                .limit(10)
                .collect(Collectors.toList());
            if (!countryNews.isEmpty()) {
                ctx.append("\nRECENT NEWS:\n");
                countryNews.forEach(h ->
                    ctx.append("  [").append(h.getOrDefault("type", "")).append("] ")
                       .append(h.getOrDefault("title", "")).append("\n"));
            }
        }

        String prompt = """
            You are Notamy News's editorial analyst. A user clicked on a Watch item to learn more.

            CONTEXT:
            %s

            WATCH ITEM: %s — %s

            Write a focused deep-dive brief on this specific situation. Same analytical density as the Daily Brief, but focused entirely on this country/topic.

            RESPOND IN EXACTLY THIS JSON FORMAT (no markdown, no backticks):
            {
              "headline": "<10-14 word thesis about this specific situation — sharp, analytical>",
              "paragraph1": "<70-90 words: what is happening with this specific situation RIGHT NOW. Concrete facts, specific details.>",
              "paragraph2": "<70-90 words: why this matters — second-order effects, operational implications, what could break next.>"
            }

            RULES:
            - Focus ONLY on this country and this situation. Do not write about the global picture.
            - Be specific: names, numbers, locations, timelines.
            - NEVER use internal scores like '78/100'. Describe situations: 'active war', 'severe food crisis'.
            - USE real numbers: casualties, displaced, price changes. NOT index values.
            - No filler. Every sentence carries new information.
            - Countries at war are at war.
            """.formatted(ctx.toString(), country, situation);

        try {
            String rawContent = callQwen(FISK_STYLE, prompt, 512, true);
            JsonNode json = extractJson(rawContent);
            if (json == null) return null;

            DeepDive dive = new DeepDive();
            dive.setCountry(country);
            dive.setSituation(situation);
            dive.setHeadline(json.path("headline").asText(""));
            dive.setParagraph1(json.path("paragraph1").asText(""));
            dive.setParagraph2(json.path("paragraph2").asText(""));
            dive.setLanguage("en");
            dive.setGeneratedAt(java.time.Instant.now().toString());

            if (dive.getHeadline().isBlank()) return null;
            log.info("Deep dive generated [en]: {} — {}", country, dive.getHeadline());
            return dive;

        } catch (Exception e) {
            log.error("Deep dive generation failed for {}: {}", country, e.getMessage());
            return null;
        }
    }

    private DeepDive translateDeepDive(DeepDive enDive, String targetLang) {
        String langName = SUPPORTED_LANGUAGES.getOrDefault(targetLang, targetLang);
        log.info("Translating deep dive ({}) to {}", enDive.getCountry(), langName);

        String source = "headline: " + enDive.getHeadline() + "\n" +
            "paragraph1: " + enDive.getParagraph1() + "\n" +
            "paragraph2: " + enDive.getParagraph2();

        String prompt = "Translate this analytical brief from English to " + langName + ".\n" +
            "Keep analytical tone, numbers as-is, country names in " + langName + ".\n\n" +
            source + "\n\n" +
            "RESPOND IN JSON (no markdown):\n" +
            "{\"headline\":\"...\",\"paragraph1\":\"...\",\"paragraph2\":\"...\"}";

        try {
            String rawContent = callQwenFlash(prompt, 512);
            JsonNode json = extractJson(rawContent);
            if (json == null) return null;

            DeepDive dive = new DeepDive();
            dive.setCountry(enDive.getCountry());
            dive.setSituation(enDive.getSituation());
            dive.setHeadline(json.path("headline").asText(""));
            dive.setParagraph1(json.path("paragraph1").asText(""));
            dive.setParagraph2(json.path("paragraph2").asText(""));
            dive.setLanguage(targetLang);
            dive.setGeneratedAt(java.time.Instant.now().toString());
            return dive.getHeadline().isBlank() ? null : dive;

        } catch (Exception e) {
            log.error("Deep dive translation to {} failed: {}", targetLang, e.getMessage());
            return null;
        }
    }

    // ==========================================
    // NOWCAST BRIEF — analytical summary of food insecurity predictions
    // ==========================================

    @Data
    public static class NowcastBrief {
        private String headline;
        private String paragraph1;
        private String paragraph2;
        private int worseningCount;
        private int stableCount;
        private int improvingCount;
        private String generatedAt;
        private String language;
        private int promptVersion;
    }

    private static final int NOWCAST_PROMPT_VERSION = 4; // v4: includes external risk divergence data

    /**
     * Generate or retrieve cached nowcast analytical brief with language support.
     */
    public NowcastBrief getNowcastBrief(String language) {
        String lang = resolveLanguage(language);
        String docId = "nowcast_" + LocalDate.now() + "_" + lang;

        // Check cache — skip if old prompt version
        Map<String, Object> cached = firestoreService.getDocument("nowcastBriefs", docId);
        if (cached != null) {
            int cachedVersion = cached.containsKey("promptVersion") ? ((Number) cached.get("promptVersion")).intValue() : 0;
            if (cachedVersion >= NOWCAST_PROMPT_VERSION) {
                return objectMapper.convertValue(cached, NowcastBrief.class);
            }
            log.info("Nowcast brief cache outdated (v{} < v{}), regenerating", cachedVersion, NOWCAST_PROMPT_VERSION);
        }

        if ("en".equals(lang)) {
            // Generate English
            NowcastBrief brief = generateNowcastBrief();
            if (brief != null) {
                brief.setLanguage("en");
                brief.setPromptVersion(NOWCAST_PROMPT_VERSION);
                saveNowcastBrief(brief, docId);
            }
            return brief;
        }

        // Non-English: get English first, then translate
        NowcastBrief enBrief = getNowcastBrief("en");
        if (enBrief == null) return null;

        log.info("Translating nowcast brief: en → {}", lang);
        NowcastBrief translated = translateNowcastBrief(enBrief, lang);
        if (translated != null) {
            saveNowcastBrief(translated, docId);
            return translated;
        }
        log.warn("Nowcast brief translation to {} failed, returning English", lang);
        return enBrief;
    }

    public NowcastBrief getNowcastBrief() {
        return getNowcastBrief("en");
    }

    private void saveNowcastBrief(NowcastBrief brief, String docId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.convertValue(brief, Map.class);
            data.put("timestamp", System.currentTimeMillis());
            firestoreService.saveDocument("nowcastBriefs", docId, data);
        } catch (Exception e) { log.error("Failed to save nowcast brief: {}", e.getMessage()); }
    }

    private NowcastBrief translateNowcastBrief(NowcastBrief enBrief, String targetLang) {
        String langName = SUPPORTED_LANGUAGES.getOrDefault(targetLang, targetLang);
        String source = "headline: " + enBrief.getHeadline() + "\n" +
            "paragraph1: " + enBrief.getParagraph1() + "\n" +
            "paragraph2: " + enBrief.getParagraph2();

        String prompt = "Translate this analytical brief from English to " + langName + ".\n" +
            "Keep analytical tone, numbers/percentages as-is, country names in " + langName + ".\n\n" +
            source + "\n\nRESPOND IN JSON (no markdown):\n{\"headline\":\"...\",\"paragraph1\":\"...\",\"paragraph2\":\"...\"}";

        try {
            String rawContent = callQwenFlash(prompt, 600);
            JsonNode json = extractJson(rawContent);
            if (json == null) return null;

            NowcastBrief translated = new NowcastBrief();
            translated.setHeadline(json.path("headline").asText(""));
            translated.setParagraph1(json.path("paragraph1").asText(""));
            translated.setParagraph2(json.path("paragraph2").asText(""));
            translated.setWorseningCount(enBrief.getWorseningCount());
            translated.setStableCount(enBrief.getStableCount());
            translated.setImprovingCount(enBrief.getImprovingCount());
            translated.setLanguage(targetLang);
            translated.setGeneratedAt(java.time.Instant.now().toString());
            translated.setPromptVersion(NOWCAST_PROMPT_VERSION);
            return translated.getHeadline().isBlank() ? null : translated;
        } catch (Exception e) {
            log.error("Nowcast brief translation to {} failed: {}", targetLang, e.getMessage());
            return null;
        }
    }

    private NowcastBrief generateNowcastBrief() {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) return null;

        var predictions = nowcastService.getNowcastAll();
        if (predictions == null || predictions.isEmpty()) return null;

        // Build comprehensive context with ALL available model data
        int worsening = 0, stable = 0, improving = 0;
        StringBuilder ctx = new StringBuilder();

        var sorted = predictions.stream()
            .sorted((a, b) -> Double.compare(
                b.getPredictedChange90d() != null ? b.getPredictedChange90d() : 0,
                a.getPredictedChange90d() != null ? a.getPredictedChange90d() : 0))
            .collect(Collectors.toList());

        for (var p : sorted) {
            double change = p.getPredictedChange90d() != null ? p.getPredictedChange90d() : 0;
            if (change > 3) worsening++;
            else if (change < -3) improving++;
            else stable++;
        }

        ctx.append("OVERVIEW: ").append(predictions.size()).append(" countries tracked. ")
           .append(worsening).append(" worsening, ").append(stable).append(" stable, ")
           .append(improving).append(" improving.\n\n");

        // WORSENING — full data
        ctx.append("WORSENING COUNTRIES (90d predicted change > +3pp):\n");
        sorted.stream().filter(p -> p.getPredictedChange90d() != null && p.getPredictedChange90d() > 3)
            .forEach(p -> {
                ctx.append("  ").append(p.getCountryName()).append(" (").append(p.getRegion()).append("):\n");
                ctx.append("    Current proxy: ").append(String.format("%.1f%%", p.getCurrentProxy()));
                ctx.append(" → Projected: ").append(String.format("%.1f%%", p.getProjectedProxy()));
                ctx.append(" (").append(String.format("%+.1fpp", p.getPredictedChange90d())).append(")\n");
                ctx.append("    FCS: ").append(String.format("%.1f%%", p.getFcsPrevalence()));
                ctx.append(p.getRcsiPrevalence() != null ?
                    ", rCSI: " + String.format("%.1f%%", p.getRcsiPrevalence()) : ", rCSI: not available");
                ctx.append(", Confidence: ").append(p.getConfidence()).append("\n");
                ctx.append("    Trend: 30d ago=").append(String.format("%.1f%%", p.getProxy30dAgo()));
                ctx.append(", 60d ago=").append(String.format("%.1f%%", p.getProxy60dAgo()));
                ctx.append(", 90d ago=").append(String.format("%.1f%%", p.getProxy90dAgo()));
                if (p.getActualChange30d() != null) ctx.append(", actual 30d change=").append(String.format("%+.1fpp", p.getActualChange30d()));
                ctx.append("\n\n");
            });

        // IMPROVING — full data for top 8
        ctx.append("IMPROVING COUNTRIES (90d predicted change < -3pp, top 8):\n");
        sorted.stream().filter(p -> p.getPredictedChange90d() != null && p.getPredictedChange90d() < -3).limit(8)
            .forEach(p -> {
                ctx.append("  ").append(p.getCountryName()).append(" (").append(p.getRegion()).append("): ");
                ctx.append(String.format("%.1f%%", p.getCurrentProxy()));
                ctx.append(" → ").append(String.format("%.1f%%", p.getProjectedProxy()));
                ctx.append(" (").append(String.format("%+.1fpp", p.getPredictedChange90d())).append(")");
                ctx.append(", confidence=").append(p.getConfidence()).append("\n");
            });

        // HIGH SEVERITY countries (proxy > 40%) regardless of trend
        ctx.append("\nHIGH SEVERITY (current proxy > 40%, regardless of trend):\n");
        sorted.stream().filter(p -> p.getCurrentProxy() != null && p.getCurrentProxy() > 40).limit(10)
            .forEach(p -> ctx.append("  ").append(p.getCountryName()).append(": ")
                .append(String.format("%.1f%%", p.getCurrentProxy()))
                .append(" (trend: ").append(String.format("%+.1fpp", p.getPredictedChange90d())).append(")\n"));

        // EXTERNAL RISK DIVERGENCE — countries where ML says stable but other signals disagree
        try {
            @SuppressWarnings("unchecked")
            List<com.crisismonitor.model.RiskScore> riskScores = cacheWarmupService.getFallback("allRiskScores");
            if (riskScores != null) {
                List<com.crisismonitor.model.RiskScore> divergent = riskScores.stream()
                    .filter(rs -> rs.getNowcastCaveat() != null)
                    .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                    .collect(Collectors.toList());
                if (!divergent.isEmpty()) {
                    ctx.append("\nEXTERNAL RISK DIVERGENCE (").append(divergent.size())
                       .append(" countries where ML predicts stable but other signals disagree):\n");
                    ctx.append("IMPORTANT: The ML model only tracks food consumption indicators. These countries have EXTERNAL risk factors ");
                    ctx.append("(conflict, climate, AI assessment) that the model cannot capture. Mention these in your analysis.\n");
                    for (var rs : divergent) {
                        ctx.append("  ").append(rs.getCountryName()).append(": ");
                        ctx.append("crisis score=").append(rs.getScore()).append("/100 (").append(rs.getRiskLevel()).append("), ");
                        ctx.append("food=").append(rs.getFoodSecurityScore()).append(", ");
                        ctx.append("conflict=").append(rs.getConflictScore()).append(", ");
                        ctx.append("climate=").append(rs.getClimateScore()).append(". ");
                        ctx.append("Warning: ").append(rs.getNowcastCaveat()).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not add divergence data to nowcast brief: {}", e.getMessage());
        }

        String prompt = "You are a crisis intelligence analyst interpreting ML model predictions.\n\n" +
            "HOW TO READ THIS DATA:\n" +
            "- 'Proxy' = composite food insecurity indicator combining:\n" +
            "  • FCS% = percentage of population with poor/borderline food consumption (based on diet diversity and frequency)\n" +
            "  • rCSI% = percentage using crisis coping strategies (skipping meals, eating less preferred foods, reducing portions)\n" +
            "  When both are available, proxy = average of FCS and rCSI. When only FCS is available, proxy = FCS only.\n" +
            "- SEVERITY THRESHOLDS (how to interpret proxy values):\n" +
            "  • <15% = manageable — most households maintain adequate food intake\n" +
            "  • 15-30% = stressed — significant minority faces food gaps\n" +
            "  • 30-50% = crisis — one third or more of population has inadequate food consumption\n" +
            "  • >50% = emergency — majority of population in severe food insecurity\n" +
            "  • >70% = catastrophe — near-total collapse of food systems\n" +
            "- DIVERGENCE: when FCS% is much higher than rCSI%, people are eating poorly but not yet coping desperately. " +
            "When rCSI% is much higher than FCS%, people are coping hard to maintain food intake — a warning of imminent collapse.\n" +
            "- rCSI 'not available' means the country lacks coping strategy data — the model relies on FCS only, which may understate severity.\n" +
            "- TREND DATA (30d/60d/90d ago): shows whether deterioration is accelerating, decelerating, or steady.\n" +
            "  If current > 30d ago > 60d ago → accelerating deterioration.\n" +
            "  If actual 30d change is larger than model predicted → model may be underestimating the speed.\n" +
            "- CONFIDENCE: HIGH = strong historical pattern match. MEDIUM = adequate data. LOW = limited data, wider uncertainty.\n\n" +
            "MODEL OUTPUT:\n" + ctx + "\n\n" +
            "Interpret STRICTLY what the model data shows. No external context, no geopolitics, no speculation.\n\n" +
            "RESPOND IN JSON (no markdown):\n" +
            "{\n" +
            "  \"headline\": \"<10-14 word analytical summary of the model's key finding>\",\n" +
            "  \"paragraph1\": \"<80-100 words: the worsening countries — how fast, from what level, to what level. " +
                "Note acceleration patterns (compare trend data). Flag where FCS/rCSI divergence signals hidden stress. " +
                "Use the severity thresholds to give context: 'pushing toward crisis levels' or 'already in emergency range'.>\",\n" +
            "  \"paragraph2\": \"<80-100 words: the broader picture — regional patterns, high-severity countries, improving outliers. " +
                "Note where confidence is LOW (wider uncertainty). Flag countries where rCSI is missing (model may understate). " +
                "CRITICAL: if EXTERNAL RISK DIVERGENCE data is present, you MUST mention it. These are countries where " +
                "the ML model says stable but conflict, climate, or AI assessment indicates otherwise. " +
                "Name the top 3-4 divergent countries and what the risk is. " +
                "One sentence on operational priority: which countries need pre-positioned resources based on this data.>\"\n" +
            "}\n\n" +
            "RULES:\n" +
            "- NEVER use the words 'survey', 'survey data', 'survey trends', or 'survey-based'. Instead say 'ground indicators', 'tracked metrics', or 'model inputs'.\n" +
            "- Use model data AND external risk divergence data if provided. No news, no agency names.\n" +
            "- Use percentage points (+5.3pp) and proxy values (38.4%).\n" +
            "- Apply severity thresholds to make numbers meaningful: don't just say '38%', say 'in crisis range at 38%'.\n" +
            "- Note data quality: flag LOW confidence predictions and missing rCSI.\n" +
            "- Dense, precise. Every sentence carries analytical value.";

        try {
            String rawContent = callQwen(AMANPOUR_STYLE, prompt, 800, false);
            JsonNode json = extractJson(rawContent);
            if (json == null) return null;

            NowcastBrief brief = new NowcastBrief();
            brief.setHeadline(json.path("headline").asText(""));
            brief.setParagraph1(json.path("paragraph1").asText(""));
            brief.setParagraph2(json.path("paragraph2").asText(""));
            brief.setWorseningCount(worsening);
            brief.setStableCount(stable);
            brief.setImprovingCount(improving);
            brief.setGeneratedAt(java.time.Instant.now().toString());

            if (brief.getHeadline().isBlank()) return null;
            log.info("Nowcast brief generated: {}", brief.getHeadline());
            return brief;

        } catch (Exception e) {
            log.error("Nowcast brief generation failed: {}", e.getMessage());
            return null;
        }
    }

    // ==========================================
    // EDITORIAL COLUMNS — Global Pulse + Field Dispatch
    // ==========================================

    @Data
    public static class EditorialColumns {
        private String globalPulseHeadline;
        private String globalPulseBody;
        private String fieldDispatchHeadline;
        private String fieldDispatchBody;
        private String language;
        private String generatedAt;
    }

    /**
     * Get or generate the two editorial columns for Overview.
     */
    public EditorialColumns getEditorialColumns(String language) {
        String lang = resolveLanguage(language);
        String docId = "columns_" + LocalDate.now() + "_" + lang;

        Map<String, Object> cached = firestoreService.getDocument("editorialColumns", docId);
        if (cached != null) {
            return objectMapper.convertValue(cached, EditorialColumns.class);
        }

        // Generate English, translate if needed
        EditorialColumns cols;
        if ("en".equals(lang)) {
            cols = generateColumns();
        } else {
            String enDocId = "columns_" + LocalDate.now() + "_en";
            Map<String, Object> enCached = firestoreService.getDocument("editorialColumns", enDocId);
            EditorialColumns enCols;
            if (enCached != null) {
                enCols = objectMapper.convertValue(enCached, EditorialColumns.class);
            } else {
                enCols = generateColumns();
                if (enCols != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> d = objectMapper.convertValue(enCols, Map.class);
                    d.put("timestamp", System.currentTimeMillis());
                    firestoreService.saveDocument("editorialColumns", enDocId, d);
                }
            }
            if (enCols == null) return null;
            cols = translateColumns(enCols, lang);
            if (cols == null) {
                log.warn("Editorial column translation to {} failed, returning English", lang);
                return enCols;
            }
        }

        if (cols != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.convertValue(cols, Map.class);
                data.put("timestamp", System.currentTimeMillis());
                firestoreService.saveDocument("editorialColumns", docId, data);
            } catch (Exception e) { log.error("Failed to save columns: {}", e.getMessage()); }
        }
        return cols;
    }

    private EditorialColumns generateColumns() {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
            log.warn("Editorial columns: DashScope API key not configured");
            return null;
        }

        // Gather media headlines and ReliefWeb reports separately
        @SuppressWarnings("unchecked")
        List<Map<String, String>> allHeadlines = cacheWarmupService.getFallback("newsHeadlines");
        if (allHeadlines == null || allHeadlines.isEmpty()) {
            log.warn("Editorial columns: no headlines available (warmup may still be in progress)");
            return null;
        }
        // Limit headlines to avoid timeout (Qwen needs <60s response time)
        if (allHeadlines.size() > 15) {
            allHeadlines = allHeadlines.subList(0, 15);
        }
        log.info("Editorial columns: generating from {} headlines", allHeadlines.size());

        StringBuilder mediaCtx = new StringBuilder("TODAY'S MEDIA HEADLINES:\n");
        StringBuilder reliefCtx = new StringBuilder("TODAY'S HUMANITARIAN REPORTS:\n");

        for (Map<String, String> h : allHeadlines) {
            String type = h.getOrDefault("type", "");
            String title = h.getOrDefault("title", "");
            String source = h.getOrDefault("source", "");
            if ("ReliefWeb".equals(type)) {
                reliefCtx.append("  - ").append(title).append(" (").append(source).append(")\n");
            } else {
                mediaCtx.append("  - ").append(title).append(" (").append(source).append(")\n");
            }
        }

        // Generate both columns with a single Qwen call
        String prompt = "You are Notamy News editorial desk. Write TWO analytical columns from the data below.\n\n" +
            "COLUMN 1 — GLOBAL PULSE (from media headlines):\n" +
            mediaCtx + "\n" +
            "COLUMN 2 — FIELD DISPATCH (from humanitarian field reports):\n" +
            reliefCtx + "\n" +
            "RESPOND IN JSON (no markdown, no backticks):\n" +
            "{\n" +
            "  \"globalPulseHeadline\": \"<8-12 word headline>\",\n" +
            "  \"globalPulseBody\": \"<100-120 words: synthesis of media narrative — what dominates, what media miss, what pattern emerges. END with 1-2 sentence FORECAST: what this media attention signals for the next 7-14 days.>\",\n" +
            "  \"fieldDispatchHeadline\": \"<8-12 word headline>\",\n" +
            "  \"fieldDispatchBody\": \"<100-120 words: operational ground truth — access, displacement, funding, response. END with 1-2 sentence FORECAST: which operations will fail or succeed in the next 7-14 days and why.>\"\n" +
            "}\n\n" +
            "RULES:\n" +
            "- Global Pulse: analytical reading of today's crisis landscape. What are the key developments? What structural trends do the headlines reveal? End with a short forward-looking assessment.\n" +
            "- Field Dispatch: operational intelligence from the ground. Concrete facts. End with a forecast.\n" +
            "- NEVER use internal scores. Describe situations concretely.\n" +
            "- No agency citations. This is YOUR editorial voice.\n" +
            "- Dense, factual, zero filler. Every sentence carries information. Professional tone — no dramatic language, no rhetoric.";

        try {
            String rawContent = callQwen(FISK_STYLE, prompt, 800, false);
            if (rawContent == null || rawContent.isBlank()) {
                log.warn("Editorial columns: Qwen returned null/blank");
                return null;
            }
            log.info("Editorial columns: Qwen returned {} chars, preview: {}", rawContent.length(), rawContent.substring(0, Math.min(200, rawContent.length())));
            JsonNode json = extractJson(rawContent);
            if (json == null) {
                log.warn("Editorial columns: extractJson returned null from content: {}", rawContent.substring(0, Math.min(500, rawContent.length())));
                return null;
            }

            EditorialColumns cols = new EditorialColumns();
            cols.setGlobalPulseHeadline(json.path("globalPulseHeadline").asText(""));
            cols.setGlobalPulseBody(json.path("globalPulseBody").asText(""));
            cols.setFieldDispatchHeadline(json.path("fieldDispatchHeadline").asText(""));
            cols.setFieldDispatchBody(json.path("fieldDispatchBody").asText(""));
            cols.setLanguage("en");
            cols.setGeneratedAt(java.time.Instant.now().toString());

            if (cols.getGlobalPulseHeadline().isBlank()) return null;
            log.info("Editorial columns generated: {} / {}", cols.getGlobalPulseHeadline(), cols.getFieldDispatchHeadline());
            return cols;

        } catch (Exception e) {
            log.error("Editorial columns generation failed: {}", e.getMessage());
            return null;
        }
    }

    private EditorialColumns translateColumns(EditorialColumns en, String targetLang) {
        String langName = SUPPORTED_LANGUAGES.getOrDefault(targetLang, targetLang);
        log.info("Translating editorial columns to {} ({})", langName, targetLang);

        String source = "globalPulseHeadline: " + en.getGlobalPulseHeadline() + "\n" +
            "globalPulseBody: " + en.getGlobalPulseBody() + "\n" +
            "fieldDispatchHeadline: " + en.getFieldDispatchHeadline() + "\n" +
            "fieldDispatchBody: " + en.getFieldDispatchBody();

        String prompt = "Translate this editorial content from English to " + langName + ".\n" +
            "Keep analytical tone, numbers/percentages as-is, country names in " + langName + ".\n\n" +
            source + "\n\n" +
            "RESPOND IN JSON (no markdown, no backticks):\n" +
            "{\"globalPulseHeadline\":\"...\",\"globalPulseBody\":\"...\",\"fieldDispatchHeadline\":\"...\",\"fieldDispatchBody\":\"...\"}";

        try {
            String rawContent = callQwenFlash(prompt, 1200);
            log.info("Column translation raw response length: {}", rawContent != null ? rawContent.length() : 0);
            JsonNode json = extractJson(rawContent);
            if (json == null) {
                log.warn("Column translation: extractJson returned null for lang={}", targetLang);
                return null;
            }

            EditorialColumns cols = new EditorialColumns();
            cols.setGlobalPulseHeadline(json.path("globalPulseHeadline").asText(""));
            cols.setGlobalPulseBody(json.path("globalPulseBody").asText(""));
            cols.setFieldDispatchHeadline(json.path("fieldDispatchHeadline").asText(""));
            cols.setFieldDispatchBody(json.path("fieldDispatchBody").asText(""));
            cols.setLanguage(targetLang);
            cols.setGeneratedAt(java.time.Instant.now().toString());
            log.info("Column translation to {} completed", targetLang);
            return cols;
        } catch (Exception e) {
            log.error("Column translation to {} failed: {}", targetLang, e.getMessage());
            return null;
        }
    }

    // ==========================================
    // QWEN API HELPER — all AI calls go through here
    // ==========================================

    private static final String FISK_STYLE = "You are a senior crisis analyst writing for a humanitarian intelligence platform. Your tone is factual, precise, and authoritative — like a UN situation report or ICG briefing. State what is happening concretely with specifics (locations, numbers, actors). Identify what matters operationally. No rhetoric, no dramatic language, no editorializing. Every sentence must carry information. End analysis sections with a short forward-looking assessment grounded in evidence.";
    private static final String AMANPOUR_STYLE = "You are a senior international analyst writing for a humanitarian audience. Your tone is clear, accessible, and data-driven. Provide context that helps operational decision-makers understand the situation. Use precise language, cite concrete facts, and maintain a professional, measured tone throughout.";

    /**
     * Call Qwen 3.5-Plus API. Returns the content text or null on error.
     */
    private String callQwen(String systemPrompt, String userPrompt, int maxTokens, boolean webSearch) {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
            log.warn("DashScope API key not configured");
            return null;
        }
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", "qwen3.5-plus");
            request.put("max_tokens", maxTokens);
            if (webSearch) request.put("enable_search", true);
            request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
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

            if (response == null || response.isBlank()) {
                log.warn("callQwen: empty response from DashScope");
                return null;
            }
            JsonNode root = objectMapper.readTree(response);
            if (root.has("choices") && root.path("choices").size() > 0) {
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                log.info("callQwen: got {} chars from choices[0]", content.length());
                return content;
            }
            JsonNode output = root.path("output");
            if (!output.isMissingNode() && output.has("choices") && output.path("choices").size() > 0) {
                String content = output.path("choices").path(0).path("message").path("content").asText("");
                log.info("callQwen: got {} chars from output.choices[0]", content.length());
                return content;
            }
            log.warn("callQwen: no choices in response: {}", response.substring(0, Math.min(300, response.length())));
            return null;
        } catch (Exception e) {
            log.error("callQwen failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Call Qwen Flash for translations — fast, cheap, good enough for translation tasks.
     * Returns the content text or null on error.
     */
    private String callQwenFlash(String userPrompt, int maxTokens) {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
            log.warn("DashScope API key not configured");
            return null;
        }
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", "qwen-flash");
            request.put("max_tokens", maxTokens);
            request.put("messages", List.of(
                Map.of("role", "system", "content", "You are a professional translator. Translate accurately, preserving analytical tone and all numbers. Respond with JSON only, no markdown."),
                Map.of("role", "user", "content", userPrompt)
            ));

            String response = qwenClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + dashscopeApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(45))
                    .block();

            if (response == null || response.isBlank()) {
                log.warn("callQwenFlash: empty response");
                return null;
            }
            JsonNode root = objectMapper.readTree(response);
            if (root.has("choices") && root.path("choices").size() > 0) {
                return root.path("choices").path(0).path("message").path("content").asText("");
            }
            return null;
        } catch (Exception e) {
            log.error("callQwenFlash failed: {}", e.getMessage());
            return null;
        }
    }

    /** Extract JSON from AI response text */
    private JsonNode extractJson(String content) {
        if (content == null || content.isBlank()) return null;
        try {
            int js = content.indexOf('{');
            int je = content.lastIndexOf('}');
            if (js < 0 || je <= js) return null;
            return objectMapper.readTree(content.substring(js, je + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Translate a batch of headlines to target language via Qwen Flash.
     */
    public List<String> translateHeadlines(List<String> headlines, String language) {
        String lang = resolveLanguage(language);
        if ("en".equals(lang) || headlines == null || headlines.isEmpty()) return headlines;

        String langName = SUPPORTED_LANGUAGES.getOrDefault(lang, lang);
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < headlines.size(); i++) {
            source.append(i + 1).append(". ").append(headlines.get(i)).append("\n");
        }

        String prompt = "Translate these news headlines from English to " + langName + ".\n" +
            "Keep the same numbering. Translate accurately, keep proper nouns.\n\n" +
            source + "\n" +
            "Respond with ONLY the translated headlines, one per line, numbered.";

        String result = callQwenFlash(prompt, 500);
        if (result == null || result.isBlank()) return headlines;

        // Parse numbered lines
        List<String> translated = new ArrayList<>();
        for (String line : result.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // Remove numbering (1. 2. etc)
            trimmed = trimmed.replaceFirst("^\\d+\\.?\\s*", "");
            if (!trimmed.isEmpty()) translated.add(trimmed);
        }

        // Return translated if count matches, otherwise original
        return translated.size() >= headlines.size() ? translated.subList(0, headlines.size()) : headlines;
    }

    private String resolveLanguage(String lang) {
        if (lang == null || lang.isBlank()) return "en";
        String code = lang.toLowerCase().trim();
        return SUPPORTED_LANGUAGES.containsKey(code) ? code : "en";
    }
}
