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
 * Cost: ~$0.02/day (1 Claude call, ~1200 tokens).
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

    @Value("${anthropic.api.key:}")
    private String apiKey;

    @Value("${anthropic.model:claude-sonnet-4-20250514}")
    private String model;

    @Value("${DASHSCOPE_API_KEY:}")
    private String dashscopeApiKey;

    private final WebClient claudeClient = WebClient.builder()
            .baseUrl("https://api.anthropic.com/v1")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .build();

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
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Claude API key not configured");
            return null;
        }

        String context = buildContext();
        String prompt = buildPrompt(context);

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model);
            request.put("max_tokens", 1024);
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            String response = claudeClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(45))
                    .block();

            DailyBrief brief = parseResponse(response);
            if (brief != null) {
                brief.setLanguage("en");
            }
            return brief;

        } catch (Exception e) {
            log.error("Claude API call for daily brief failed: {}", e.getMessage());
            return null;
        }
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

        // Verified conflicts — ground truth
        ctx.append("\nVERIFIED ARMED CONFLICTS (analyst-confirmed):\n");
        ctx.append("  Palestine: Israeli military operations in Gaza, siege, ground invasion\n");
        ctx.append("  Iran: US/Israel-Iran war since Feb 2026, Hormuz blockade, ongoing airstrikes\n");
        ctx.append("  Ukraine: Russia full-scale invasion since 2022\n");
        ctx.append("  Sudan: Civil war SAF vs RSF, 150K+ estimated dead\n");
        ctx.append("  Myanmar: Nationwide civil war, junta controls ~21% of territory\n");
        ctx.append("  Yemen: Houthi conflict + US/coalition strikes, Red Sea disruption\n");
        ctx.append("  Syria: Post-Assad transition instability, multi-faction\n");

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

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model);
            request.put("max_tokens", 1024);
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            String response = claudeClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            DailyBrief translated = parseResponse(response);
            if (translated != null) {
                translated.setLanguage(targetLang);
            }
            return translated;

        } catch (Exception e) {
            log.error("Translation to {} failed: {}", targetLang, e.getMessage());
            return null;
        }
    }

    private DailyBrief parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentArr = root.path("content");
            if (!contentArr.isArray() || contentArr.isEmpty()) {
                log.warn("Claude response has no content array");
                return null;
            }
            String content = contentArr.get(0).path("text").asText("");
            if (content.isBlank()) {
                log.warn("Claude response text is empty");
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
     */
    public DailyBrief getTodayBrief(String language) {
        String lang = resolveLanguage(language);
        String docId = LocalDate.now() + "_" + lang;
        Map<String, Object> data = firestoreService.getDocument("dailyBriefs", docId);
        if (data != null) {
            return objectMapper.convertValue(data, DailyBrief.class);
        }
        // English fallback: if requested language doesn't exist, try English
        if (!"en".equals(lang)) {
            String enDocId = LocalDate.now() + "_en";
            data = firestoreService.getDocument("dailyBriefs", enDocId);
            if (data != null) {
                return objectMapper.convertValue(data, DailyBrief.class);
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
        if (apiKey == null || apiKey.isBlank()) return null;

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

        // Verified conflicts
        Map<String, String> conflicts = Map.of(
            "PSE", "Active war: Israeli military operations in Gaza",
            "IRN", "Active war: US/Israel-Iran military conflict since Feb 2026",
            "UKR", "Active war: Russia full-scale invasion since 2022",
            "SDN", "Civil war: SAF vs RSF, 150K+ dead",
            "ISR", "Multi-front: Gaza + Iran war",
            "MMR", "Nationwide civil war: resistance vs junta",
            "YEM", "Houthi conflict + US strikes + Red Sea disruption",
            "SYR", "Post-Assad transition instability"
        );
        String conflict = conflicts.get(iso3);
        if (conflict != null) {
            ctx.append("VERIFIED CONFLICT: ").append(conflict).append("\n\n");
        }

        String prompt = "You are a senior intelligence analyst at Notamy News. Write a Country Intelligence Brief for " + countryName + ".\n\n" +
            "PLATFORM DATA:\n" + ctx + "\n" +
            "Write 5 analytical sections. Each section: 2-3 sentences, dense, specific. No filler.\n\n" +
            "RESPOND IN JSON (no markdown, no backticks):\n" +
            "{\n" +
            "  \"security\": \"<2-3 sentences: political situation, governance, armed conflict, internal tensions>\",\n" +
            "  \"economy\": \"<2-3 sentences: inflation, sanctions, employment, currency, trade disruptions>\",\n" +
            "  \"foodSecurity\": \"<2-3 sentences: food insecurity levels, supply chain, prices, nowcast trajectory>\",\n" +
            "  \"displacement\": \"<2-3 sentences: IDP flows, refugees, migration routes, humanitarian access>\",\n" +
            "  \"outlook\": \"<2-3 sentences: 90-day forecast, what could improve or worsen, key triggers to watch>\"\n" +
            "}\n\n" +
            "RULES:\n" +
            "- Countries at war ARE at war. State it directly.\n" +
            "- NEVER use internal platform scores like '78/100' or '88/100' — these are meaningless to readers.\n" +
            "- Instead DESCRIBE: 'severe food crisis', 'economic collapse', 'active bombardment'.\n" +
            "- USE real-world numbers: death tolls, displaced people, price changes. NOT index values.\n" +
            "- No agency citations (WFP, UNHCR, FAO). Describe situations, not sources.\n" +
            "- If data is limited for a dimension, say what you know and note the gap.\n" +
            "- Write as if briefing a country director arriving tomorrow.";

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model);
            request.put("max_tokens", 800);
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            String response = claudeClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(45))
                    .block();

            JsonNode root = objectMapper.readTree(response);
            JsonNode contentArr = root.path("content");
            if (!contentArr.isArray() || contentArr.isEmpty()) return null;
            String content = contentArr.get(0).path("text").asText("");
            int js = content.indexOf('{');
            int je = content.lastIndexOf('}');
            if (js < 0 || je <= js) return null;
            JsonNode json = objectMapper.readTree(content.substring(js, je + 1));

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
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model);
            request.put("max_tokens", 800);
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            String response = claudeClient.post()
                    .uri("/messages").header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01").header("content-type", "application/json")
                    .bodyValue(request).retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30)).block();

            JsonNode root = objectMapper.readTree(response);
            JsonNode cArr = root.path("content");
            if (!cArr.isArray() || cArr.isEmpty()) return null;
            String content = cArr.get(0).path("text").asText("");
            if (content.isBlank()) return null;
            int js = content.indexOf('{'); int je = content.lastIndexOf('}');
            if (js < 0 || je <= js) return null;
            JsonNode json = objectMapper.readTree(content.substring(js, je + 1));

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
        if (apiKey == null || apiKey.isBlank()) return null;

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

        // News about this country
        @SuppressWarnings("unchecked")
        List<Map<String, String>> headlines = cacheWarmupService.getFallback("newsHeadlines");
        if (headlines != null) {
            String countryLower = country.toLowerCase();
            var countryNews = headlines.stream()
                .filter(h -> {
                    String title = h.getOrDefault("title", "").toLowerCase();
                    String hIso3 = h.getOrDefault("iso3", "");
                    // Match by country name in title OR exact ISO3 match
                    return title.contains(countryLower) || countryLower.equalsIgnoreCase(hIso3);
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
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model);
            request.put("max_tokens", 512);
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            String response = claudeClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            JsonNode root = objectMapper.readTree(response);
            JsonNode contentArr = root.path("content");
            if (!contentArr.isArray() || contentArr.isEmpty()) return null;
            String content = contentArr.get(0).path("text").asText("");

            int jsonStart = content.indexOf('{');
            int jsonEnd = content.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd <= jsonStart) return null;
            JsonNode json = objectMapper.readTree(content.substring(jsonStart, jsonEnd + 1));

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
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model);
            request.put("max_tokens", 512);
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            String response = claudeClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();

            JsonNode root = objectMapper.readTree(response);
            JsonNode contentArr = root.path("content");
            if (!contentArr.isArray() || contentArr.isEmpty()) return null;
            String content = contentArr.get(0).path("text").asText("");
            int js = content.indexOf('{');
            int je = content.lastIndexOf('}');
            if (js < 0 || je <= js) return null;
            JsonNode json = objectMapper.readTree(content.substring(js, je + 1));

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
    }

    /**
     * Generate or retrieve cached nowcast analytical brief.
     */
    public NowcastBrief getNowcastBrief() {
        String docId = "nowcast_" + LocalDate.now();

        // Check cache
        Map<String, Object> cached = firestoreService.getDocument("nowcastBriefs", docId);
        if (cached != null) {
            return objectMapper.convertValue(cached, NowcastBrief.class);
        }

        // Generate
        NowcastBrief brief = generateNowcastBrief();
        if (brief != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.convertValue(brief, Map.class);
                data.put("timestamp", System.currentTimeMillis());
                firestoreService.saveDocument("nowcastBriefs", docId, data);
            } catch (Exception e) { /* logged below */ }
        }
        return brief;
    }

    private NowcastBrief generateNowcastBrief() {
        if (apiKey == null || apiKey.isBlank()) return null;

        var predictions = nowcastService.getNowcastAll();
        if (predictions == null || predictions.isEmpty()) return null;

        // Build context
        int worsening = 0, stable = 0, improving = 0;
        StringBuilder ctx = new StringBuilder();
        ctx.append("Date: ").append(LocalDate.now()).append("\n\n");
        ctx.append("FOOD INSECURITY NOWCAST PREDICTIONS (90-day outlook):\n");
        ctx.append("Model: 4-model ONNX ensemble, R²=0.983, MAE=1.20pp\n\n");

        // Sort by worst deterioration
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

        ctx.append("SUMMARY: ").append(worsening).append(" worsening, ")
           .append(stable).append(" stable, ").append(improving).append(" improving\n\n");

        // Top worsening
        ctx.append("WORSENING (predicted food insecurity increase):\n");
        sorted.stream().filter(p -> p.getPredictedChange90d() != null && p.getPredictedChange90d() > 3).limit(10)
            .forEach(p -> ctx.append("  ").append(p.getCountryName())
                .append(": current=").append(String.format("%.1f%%", p.getCurrentProxy()))
                .append(", predicted=").append(String.format("%+.1fpp", p.getPredictedChange90d()))
                .append(" → ").append(String.format("%.1f%%", p.getProjectedProxy()))
                .append("\n"));

        // Top improving
        ctx.append("\nIMPROVING (predicted food insecurity decrease):\n");
        sorted.stream().filter(p -> p.getPredictedChange90d() != null && p.getPredictedChange90d() < -3).limit(5)
            .forEach(p -> ctx.append("  ").append(p.getCountryName())
                .append(": current=").append(String.format("%.1f%%", p.getCurrentProxy()))
                .append(", predicted=").append(String.format("%+.1fpp", p.getPredictedChange90d()))
                .append("\n"));

        // FAO context
        try {
            var fao = faoFoodPriceService.getLatest();
            if (fao != null) {
                ctx.append("\nGLOBAL FOOD PRICES (FAO): Food=")
                   .append(String.format("%.1f", fao.getFoodIndex()))
                   .append(", Cereals=").append(String.format("%.1f", fao.getCerealsIndex()))
                   .append("\n");
            }
        } catch (Exception e) { /* skip */ }

        String prompt = "You are Notamy News's food security analyst. You have access to a proprietary 4-model ONNX ensemble " +
            "(LightGBM + XGBoost, R²=0.983, MAE=1.20pp) that predicts 90-day changes in food insecurity.\n\n" +
            "WHAT THE DATA MEANS:\n" +
            "- 'Proxy' = average of two WFP indicators: % with poor food consumption (FCS) and % using crisis coping strategies (rCSI)\n" +
            "- '90d Change' = predicted percentage point change in the proxy over 90 days. Positive = food insecurity WORSENING\n" +
            "- Countries marked WORSENING have predicted change > +3pp. IMPROVING < -3pp\n" +
            "- FCS% = people with poor/borderline food consumption. rCSI% = people using reduced coping strategies\n" +
            "- Data comes from WFP HungerMap LIVE phone surveys (near-real-time, not annual)\n\n" +
            "DATA:\n" + ctx + "\n\n" +
            "Write a concise analytical reading of these predictions. Think: what would a WFP country director need to know?\n\n" +
            "RESPOND IN JSON (no markdown):\n" +
            "{\n" +
            "  \"headline\": \"<10-14 word analytical thesis about the food insecurity outlook>\",\n" +
            "  \"paragraph1\": \"<70-90 words: which countries are deteriorating, how fast, specific numbers. Name the worst cases.>\",\n" +
            "  \"paragraph2\": \"<70-90 words: regional patterns, conflict-hunger nexus, what supply/access disruptions are driving this. One forward-looking sentence.>\"\n" +
            "}\n\n" +
            "RULES:\n" +
            "- Specific country names and real-world numbers (displaced people, price changes). No filler.\n" +
            "- NEVER use internal scores like '38.4/100' or 'proxy 58.2%'. Instead describe: 'severe food insecurity', 'critical hunger levels'.\n" +
            "- Percentage point changes (+5.3pp) are OK because they describe real predicted deterioration.\n" +
            "- No agency citations. This is YOUR analysis.\n" +
            "- Write like a senior analyst briefing an executive director.";

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model);
            request.put("max_tokens", 512);
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            String response = claudeClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            JsonNode root = objectMapper.readTree(response);
            JsonNode contentArr = root.path("content");
            if (!contentArr.isArray() || contentArr.isEmpty()) return null;
            String content = contentArr.get(0).path("text").asText("");
            int js = content.indexOf('{');
            int je = content.lastIndexOf('}');
            if (js < 0 || je <= js) return null;
            JsonNode json = objectMapper.readTree(content.substring(js, je + 1));

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
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) return null;

        // Gather media headlines and ReliefWeb reports separately
        @SuppressWarnings("unchecked")
        List<Map<String, String>> allHeadlines = cacheWarmupService.getFallback("newsHeadlines");
        if (allHeadlines == null || allHeadlines.isEmpty()) return null;

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
            "  \"globalPulseHeadline\": \"<8-12 word headline summarizing what global media is focused on today>\",\n" +
            "  \"globalPulseBody\": \"<80-100 words: synthesis of the media narrative. What story dominates? What are media missing? What pattern emerges?>\",\n" +
            "  \"fieldDispatchHeadline\": \"<8-12 word headline summarizing field operations today>\",\n" +
            "  \"fieldDispatchBody\": \"<80-100 words: what humanitarian operations report from the ground. Access issues, displacement, funding gaps, response capacity.>\"\n" +
            "}\n\n" +
            "RULES:\n" +
            "- Global Pulse: analytical media reading, not a news summary. What does media coverage REVEAL about the crisis landscape?\n" +
            "- Field Dispatch: operational intelligence. What do humanitarians on the ground need to know TODAY?\n" +
            "- NEVER use internal scores. Describe situations concretely.\n" +
            "- No agency citations. This is YOUR editorial voice.\n" +
            "- Dense, analytical, zero filler. Every sentence carries information.";

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", "qwen3.5-plus");
            request.put("max_tokens", 600);
            request.put("enable_search", true);
            request.put("messages", List.of(
                Map.of("role", "system", "content", "You are a humanitarian intelligence editorial desk. Write sharp analytical prose."),
                Map.of("role", "user", "content", prompt)
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

            JsonNode root = objectMapper.readTree(response);
            String content = "";
            if (root.has("choices") && root.path("choices").size() > 0) {
                content = root.path("choices").path(0).path("message").path("content").asText("");
            }
            if (content.isBlank()) return null;

            int js = content.indexOf('{');
            int je = content.lastIndexOf('}');
            if (js < 0 || je <= js) return null;
            JsonNode json = objectMapper.readTree(content.substring(js, je + 1));

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
        String source = "globalPulseHeadline: " + en.getGlobalPulseHeadline() + "\n" +
            "globalPulseBody: " + en.getGlobalPulseBody() + "\n" +
            "fieldDispatchHeadline: " + en.getFieldDispatchHeadline() + "\n" +
            "fieldDispatchBody: " + en.getFieldDispatchBody();

        String prompt = "Translate this editorial content from English to " + langName + ".\n" +
            "Keep analytical tone, numbers as-is.\n\n" + source + "\n\n" +
            "RESPOND IN JSON (no markdown):\n" +
            "{\"globalPulseHeadline\":\"...\",\"globalPulseBody\":\"...\",\"fieldDispatchHeadline\":\"...\",\"fieldDispatchBody\":\"...\"}";

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", "qwen3.5-plus");
            request.put("max_tokens", 600);
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            String response = qwenClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + dashscopeApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(request).retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30)).block();

            JsonNode root = objectMapper.readTree(response);
            String content = root.has("choices") && root.path("choices").size() > 0
                ? root.path("choices").path(0).path("message").path("content").asText("") : "";
            int js = content.indexOf('{'); int je = content.lastIndexOf('}');
            if (js < 0 || je <= js) return null;
            JsonNode json = objectMapper.readTree(content.substring(js, je + 1));

            EditorialColumns cols = new EditorialColumns();
            cols.setGlobalPulseHeadline(json.path("globalPulseHeadline").asText(""));
            cols.setGlobalPulseBody(json.path("globalPulseBody").asText(""));
            cols.setFieldDispatchHeadline(json.path("fieldDispatchHeadline").asText(""));
            cols.setFieldDispatchBody(json.path("fieldDispatchBody").asText(""));
            cols.setLanguage(targetLang);
            cols.setGeneratedAt(java.time.Instant.now().toString());
            return cols;
        } catch (Exception e) {
            log.error("Column translation failed: {}", e.getMessage());
            return null;
        }
    }

    private String resolveLanguage(String lang) {
        if (lang == null || lang.isBlank()) return "en";
        String code = lang.toLowerCase().trim();
        return SUPPORTED_LANGUAGES.containsKey(code) ? code : "en";
    }
}
