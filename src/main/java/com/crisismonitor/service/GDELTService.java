package com.crisismonitor.service;

import com.crisismonitor.model.ConflictEvent;
import com.crisismonitor.model.Headline;
import com.crisismonitor.model.MediaSpike;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.crisismonitor.config.MonitoredCountries;

import java.time.LocalDate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * GDELT Service - Zero-key conflict and media monitoring layer
 *
 * Purpose: Real-time conflict/crisis detection via news volume and tone
 * NOT ground truth validation (that's ACLED's job when available)
 *
 * Key outputs:
 * - Conflict Media Spike Index (z-score based)
 * - Geo hot-spots (news attention concentration)
 * - Tone/sentiment trends (negative = crisis indicator)
 *
 * API Docs: https://blog.gdeltproject.org/gdelt-2-0-our-global-world-in-realtime/
 */
@Slf4j
@Service
public class GDELTService {

    private final WebClient gdeltClient;

    // GDELT API endpoints
    private static final String GDELT_DOC_API = "https://api.gdeltproject.org/api/v2/doc/doc";
    private static final String GDELT_GEO_API = "https://api.gdeltproject.org/api/v2/geo/geo";

    // Conflict-related keywords for queries
    private static final String CONFLICT_KEYWORDS =
        "(conflict OR violence OR military OR attack OR bombing OR casualties OR " +
        "fighting OR clashes OR armed OR war OR terrorism OR insurgent OR militia)";

    private static final String CRISIS_KEYWORDS =
        "(crisis OR emergency OR disaster OR humanitarian OR famine OR drought OR " +
        "displacement OR refugees OR epidemic OR outbreak)";

    // Global rate limiter - GDELT requires significant gaps between requests
    // Cloud Run IPs get 429 at 15s intervals; 30s more reliable based on prod logs
    private static final long RATE_LIMIT_MS = 45000; // 45 seconds between requests (30s gets 429 on Cloud Run)
    private static final AtomicLong lastRequestTime = new AtomicLong(0);

    // Baseline cache: stores 30d article counts with timestamps to avoid re-fetching
    // 30d window changes slowly — refreshing every 4 hours saves ~40% of API calls
    private static final long BASELINE_TTL_MS = 4 * 60 * 60 * 1000; // 4 hours
    private static final ConcurrentHashMap<String, CachedBaseline> baselineCache = new ConcurrentHashMap<>();

    private record CachedBaseline(int count30d, long timestamp) {
        boolean isValid() {
            return System.currentTimeMillis() - timestamp < BASELINE_TTL_MS;
        }
    }

    public GDELTService() {
        this.gdeltClient = WebClient.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    // Geopolitical keywords that capture military/sanctions tensions beyond generic "conflict"
    private static final String GEOPOLITICAL_KEYWORDS =
        "(conflict OR military OR attack OR bombing OR missile OR sanctions OR strike OR naval OR nuclear OR airstrike OR war)";

    // Lock for thread-safe rate limiting — held across entire HTTP request
    // so the 30s gap is measured from response completion, not request start
    private static final java.util.concurrent.locks.ReentrantLock RATE_LIMIT_LOCK =
            new java.util.concurrent.locks.ReentrantLock(true);

    /**
     * Build a GDELT query using country aliases for broader conflict coverage.
     * Instead of just "iran" conflict, uses ("iran" OR "irgc" OR "strait of hormuz") + geopolitical keywords.
     * This captures military tensions, sanctions pressure, and proxy conflicts.
     */
    private String buildCountryConflictQuery(String iso3, String countryName) {
        List<String> aliases = MonitoredCountries.COUNTRY_ALIASES.get(iso3);

        if (aliases == null || aliases.size() <= 1) {
            // Simple case: no useful aliases
            // Don't quote short single-word terms (GDELT rejects quoted phrases < 5 chars)
            String term = countryName.contains(" ") || countryName.length() >= 5
                    ? "(\"" + countryName + "\")"
                    : "(" + countryName + ")";
            return String.format("%s %s", term, GEOPOLITICAL_KEYWORDS);
        }

        // Pick the most useful aliases (max 4 to keep query manageable)
        // Skip pure demonyms (e.g., "iranian") as they add noise
        List<String> queryTerms = new ArrayList<>();
        // Don't quote short single-word terms (GDELT rejects quoted phrases < 5 chars)
        if (countryName.contains(" ") || countryName.length() >= 5) {
            queryTerms.add("\"" + countryName + "\"");
        } else {
            queryTerms.add(countryName);
        }

        int added = 0;
        for (String alias : aliases) {
            if (added >= 3) break;
            // Skip the country name itself (already added) and short demonyms
            if (alias.equals(countryName) || alias.endsWith("an") && alias.length() < 8) continue;
            // Don't quote short aliases (GDELT rejects < 5 chars)
            if (alias.contains(" ") || alias.length() >= 5) {
                queryTerms.add("\"" + alias + "\"");
            } else {
                queryTerms.add(alias);
            }
            added++;
        }

        String countryPart = "(" + String.join(" OR ", queryTerms) + ")";
        return countryPart + " " + GEOPOLITICAL_KEYWORDS;
    }

    /**
     * Acquire rate limit — waits 30s from last GDELT response, then holds the lock.
     * Caller MUST call releaseRateLimit() in a finally block after the HTTP response.
     * This ensures the 30s gap is measured from response-to-request, not request-to-request.
     */
    private void acquireRateLimit() {
        RATE_LIMIT_LOCK.lock();
        long elapsed = System.currentTimeMillis() - lastRequestTime.get();
        if (elapsed < RATE_LIMIT_MS) {
            long waitTime = RATE_LIMIT_MS - elapsed;
            log.debug("GDELT rate limit: waiting {}ms", waitTime);
            try {
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                RATE_LIMIT_LOCK.unlock();
            }
        }
    }

    /**
     * Release rate limit — sets lastRequestTime to now and releases the lock.
     */
    private void releaseRateLimit() {
        lastRequestTime.set(System.currentTimeMillis());
        RATE_LIMIT_LOCK.unlock();
    }

    /**
     * Call GDELT API with automatic rate limiting and 429 retry.
     * Retries once with 90s backoff on 429, then gives up.
     */
    private String callGdeltApi(String url) {
        for (int attempt = 0; attempt < 2; attempt++) {
            acquireRateLimit();
            try {
                String response = gdeltClient.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                return response;
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException.TooManyRequests e) {
                log.warn("GDELT 429, attempt {}/2 — backing off 90s", attempt + 1);
                // Don't count this as a real request for rate limiting purposes
            } catch (Exception e) {
                throw e;
            } finally {
                releaseRateLimit();
            }

            // Extra backoff on 429 (on top of the 45s rate limit wait on next acquire)
            try {
                Thread.sleep(90000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * Call GDELT API returning JsonNode, with rate limiting and 429 retry.
     */
    private JsonNode callGdeltApiJson(String url) {
        for (int attempt = 0; attempt < 2; attempt++) {
            acquireRateLimit();
            try {
                JsonNode response = gdeltClient.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();
                return response;
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException.TooManyRequests e) {
                log.warn("GDELT 429, attempt {}/2 — backing off 90s", attempt + 1);
            } catch (Exception e) {
                throw e;
            } finally {
                releaseRateLimit();
            }

            try {
                Thread.sleep(90000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * Get conflict article count for a country over specified days.
     * Uses simple "country" + "conflict" query to avoid hitting the 250 maxrecords cap
     * (broader alias queries return 250 for almost every country, making z-scores identical).
     * Country aliases are used separately in multi-signal scoring.
     */
    @Cacheable(value = "gdeltConflictCount", key = "#iso3 + '-' + #days")
    public int getConflictArticleCount(String iso3, int days) {
        String countryName = MonitoredCountries.getGdeltTerm(iso3);
        log.info("Fetching GDELT conflict count for {} ({} days)", countryName, days);

        try {
            // Simple focused query — avoids saturating the 250 maxrecords cap
            // Don't quote short single-word terms (GDELT rejects quoted phrases < 5 chars)
            String query = countryName.contains(" ") || countryName.length() >= 5
                    ? String.format("\"%s\" conflict", countryName)
                    : String.format("%s conflict", countryName);

            String url = UriComponentsBuilder.fromHttpUrl(GDELT_DOC_API)
                    .queryParam("query", query)
                    .queryParam("mode", "artlist")
                    .queryParam("maxrecords", "250")
                    .queryParam("timespan", days + "d")
                    .queryParam("format", "json")
                    .build()
                    .toUriString();

            // Rate limit: hold lock across entire HTTP request so 30s gap is from response-to-request
            String responseStr = callGdeltApi(url);

            if (responseStr == null || responseStr.isBlank()) {
                log.debug("GDELT: Empty response for {}", countryName);
                return 0;
            }

            // Parse JSON - GDELT sometimes returns non-JSON for empty results
            String trimmed = responseStr.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                log.warn("GDELT: Non-JSON response for {}: {}", countryName,
                        trimmed.substring(0, Math.min(200, trimmed.length())));
                return 0;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode response = mapper.readTree(responseStr);

            if (response != null && response.has("articles")) {
                int count = response.get("articles").size();
                log.info("GDELT: {} conflict articles for {} in {}d", count, countryName, days);
                return count;
            }
            return 0;

        } catch (Exception e) {
            log.error("Error fetching GDELT conflict count for {}: {}", iso3, e.getMessage());
            return 0;
        }
    }

    /**
     * Calculate Conflict Media Spike Index using z-score
     * Compares recent activity (7d) against baseline (30d)
     *
     * @return z-score where > 2.0 indicates significant spike
     */
    @Cacheable(value = "gdeltSpikeIndex", key = "#iso3")
    public MediaSpike getConflictSpikeIndex(String iso3) {
        log.info("Calculating GDELT spike index for {}", iso3);

        try {
            int count7d = getConflictArticleCount(iso3, 7);

            // Use cached 30d baseline when available (saves an API call per country)
            int count30d;
            CachedBaseline cached = baselineCache.get(iso3);
            if (cached != null && cached.isValid()) {
                count30d = cached.count30d();
                log.debug("Using cached 30d baseline for {}: {} articles", iso3, count30d);
            } else {
                count30d = getConflictArticleCount(iso3, 30);
                if (count30d > 0) {
                    baselineCache.put(iso3, new CachedBaseline(count30d, System.currentTimeMillis()));
                }
            }

            // Handle GDELT maxrecords=250 cap: when count hits 250, the real count is unknown (≥250).
            // If both periods are capped, z-score is meaningless (all countries get the same score).
            // Strategy: if 7d is capped, mark as high-volume conflict (skip z-score entirely).
            boolean capped7d = count7d >= 250;
            boolean capped30d = count30d >= 250;

            double avg7d = count7d / 7.0;
            double avg30d = count30d / 30.0;
            double zScore;
            String level;

            if (capped7d && capped30d) {
                // Both periods saturated — this is a chronic high-coverage conflict zone
                // Cannot calculate meaningful spike, but high absolute coverage = significant conflict
                zScore = 1.5; // Mark as ELEVATED (confirmed active conflict, but no spike data)
                level = "ELEVATED";
            } else if (capped7d) {
                // Recent period saturated but baseline wasn't — this IS a spike
                zScore = 3.0;
                level = "CRITICAL";
            } else {
                // Normal calculation — neither period is capped
                double baselineStd = Math.max(avg30d * 0.3, 1.0);
                zScore = (avg7d - avg30d) / baselineStd;

                if (zScore > 3.0) level = "CRITICAL";
                else if (zScore > 2.0) level = "HIGH";
                else if (zScore > 1.0) level = "ELEVATED";
                else if (zScore > 0.5) level = "MODERATE";
                else level = "NORMAL";
            }

            // Get top headlines for context (only for notable activity)
            List<String> headlines = null;
            if (zScore > 0.5 || capped7d) {
                headlines = getTopHeadlines(iso3, 3);
            }

            return MediaSpike.builder()
                    .iso3(iso3)
                    .countryName(MonitoredCountries.getName(iso3))
                    .articlesLast7Days(count7d)
                    .articlesLast30Days(count30d)
                    .dailyAvg7d(avg7d)
                    .dailyAvg30d(avg30d)
                    .zScore(Math.round(zScore * 100.0) / 100.0)
                    .spikeLevel(level)
                    .topHeadlines(headlines)
                    .calculatedAt(LocalDate.now())
                    .build();

        } catch (Exception e) {
            log.error("Error calculating spike index for {}: {}", iso3, e.getMessage());
            return MediaSpike.builder()
                    .iso3(iso3)
                    .spikeLevel("ERROR")
                    .build();
        }
    }

    /**
     * Get top English headlines for a country
     * Provides context for why there's a media spike
     *
     * @param iso3 Country ISO3 code
     * @param limit Maximum number of headlines
     * @param days Timespan in days
     * @param topicKeywords Topic keywords (null defaults to "conflict")
     */
    @Cacheable(value = "gdeltHeadlines", key = "#iso3 + '-' + #limit + '-' + #days + '-' + #topicKeywords")
    public List<String> getTopHeadlines(String iso3, int limit, int days, String topicKeywords) {
        String countryName = MonitoredCountries.getGdeltTerm(iso3);
        String keywords = (topicKeywords != null && !topicKeywords.isBlank()) ? topicKeywords : "conflict";
        String timespan = days + "d";

        log.info("Fetching top {} headlines for {} with keywords '{}' over {} days", limit, countryName, keywords, days);

        try {
            // Query for English articles only with topic-specific keywords
            // Note: GDELT only allows parentheses around OR statements
            // Don't quote short single-word terms (GDELT rejects quoted phrases < 5 chars)
            String countryPart = countryName.contains(" ") || countryName.length() >= 5
                    ? "\"" + countryName + "\"" : countryName;
            String query = String.format("%s %s sourcelang:english", countryPart, keywords);

            String url = UriComponentsBuilder.fromHttpUrl(GDELT_DOC_API)
                    .queryParam("query", query)
                    .queryParam("mode", "artlist")
                    .queryParam("maxrecords", String.valueOf(limit + 5)) // Get extra to filter duplicates
                    .queryParam("timespan", timespan)
                    .queryParam("format", "json")
                    .build()
                    .toUriString();

            String responseStr = callGdeltApi(url);

            if (responseStr == null || responseStr.isBlank()) {
                return Collections.emptyList();
            }

            String trimmed = responseStr.trim();
            if (!trimmed.startsWith("{")) {
                return Collections.emptyList();
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode response = mapper.readTree(responseStr);

            List<String> headlines = new ArrayList<>();
            Set<String> seen = new HashSet<>(); // Dedup similar headlines

            if (response != null && response.has("articles")) {
                for (JsonNode article : response.get("articles")) {
                    if (headlines.size() >= limit) break;

                    String title = article.has("title") ? article.get("title").asText() : null;
                    if (title != null && !title.isBlank()) {
                        // Truncate and clean up
                        title = title.length() > 70 ? title.substring(0, 67) + "..." : title;
                        // Simple dedup: check if first 30 chars are unique
                        String key = title.substring(0, Math.min(30, title.length())).toLowerCase();
                        if (!seen.contains(key)) {
                            seen.add(key);
                            headlines.add(title);
                        }
                    }
                }
            }

            log.info("Got {} headlines for {}", headlines.size(), countryName);
            return headlines;

        } catch (Exception e) {
            log.error("Error fetching headlines for {}: {}", iso3, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Backward-compatible overload (uses conflict keywords, 7 days)
     */
    public List<String> getTopHeadlines(String iso3, int limit) {
        return getTopHeadlines(iso3, limit, 7, "conflict");
    }

    /**
     * Get top headlines with URLs for traceability.
     * Returns Headline objects with title, url, and source domain.
     *
     * @param iso3 Country ISO3 code
     * @param limit Maximum number of headlines to return
     * @param days Timespan in days (7, 30, 90)
     * @param topicKeywords Topic-specific keywords to search (e.g., "migration OR refugees")
     *                      If null or empty, defaults to "conflict"
     */
    @Cacheable(value = "gdeltHeadlinesWithUrl", key = "#iso3 + '-' + #limit + '-' + #days + '-' + #topicKeywords")
    public List<Headline> getTopHeadlinesWithUrls(String iso3, int limit, int days, String topicKeywords) {
        String countryName = MonitoredCountries.getGdeltTerm(iso3);

        // Use topic keywords or default to "conflict"
        String keywords = (topicKeywords != null && !topicKeywords.isBlank()) ? topicKeywords : "conflict";
        String timespan = days + "d";

        log.info("Fetching top {} headlines for {} with keywords '{}' over {} days", limit, countryName, keywords, days);

        try {
            // Build query with topic-specific keywords
            // Note: GDELT only allows parentheses around OR statements
            // Don't quote short single-word terms (GDELT rejects quoted phrases < 5 chars)
            String countryPart = countryName.contains(" ") || countryName.length() >= 5
                    ? "\"" + countryName + "\"" : countryName;
            String query = String.format("%s %s sourcelang:english", countryPart, keywords);

            String url = UriComponentsBuilder.fromHttpUrl(GDELT_DOC_API)
                    .queryParam("query", query)
                    .queryParam("mode", "artlist")
                    .queryParam("maxrecords", String.valueOf(limit + 10))
                    .queryParam("timespan", timespan)
                    .queryParam("format", "json")
                    .build()
                    .toUriString();

            // Rate limit before API call
            String responseStr = callGdeltApi(url);

            if (responseStr == null || responseStr.isBlank()) {
                return Collections.emptyList();
            }

            String trimmed = responseStr.trim();
            if (!trimmed.startsWith("{")) {
                return Collections.emptyList();
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode response = mapper.readTree(responseStr);

            List<Headline> headlines = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            if (response != null && response.has("articles")) {
                for (JsonNode article : response.get("articles")) {
                    if (headlines.size() >= limit) break;

                    String title = article.has("title") ? article.get("title").asText() : null;
                    String articleUrl = article.has("url") ? article.get("url").asText() : null;
                    String domain = article.has("domain") ? article.get("domain").asText() : null;

                    if (title != null && !title.isBlank()) {
                        // Truncate long titles
                        String displayTitle = title.length() > 80 ? title.substring(0, 77) + "..." : title;
                        // Dedup
                        String key = title.substring(0, Math.min(30, title.length())).toLowerCase();
                        if (!seen.contains(key)) {
                            seen.add(key);
                            headlines.add(Headline.builder()
                                    .title(displayTitle)
                                    .url(articleUrl)
                                    .source(domain)
                                    .build());
                        }
                    }
                }
            }

            log.info("Got {} headlines with URLs for {}", headlines.size(), countryName);
            return headlines;

        } catch (Exception e) {
            log.error("Error fetching headlines with URLs for {}: {}", iso3, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Backward-compatible overload for spike detection (uses conflict keywords, 7 days)
     */
    public List<Headline> getTopHeadlinesWithUrls(String iso3, int limit) {
        return getTopHeadlinesWithUrls(iso3, limit, 7, "conflict");
    }

    /**
     * Get average tone (sentiment) for country coverage
     * Negative tone correlates with crisis situations
     *
     * @return average tone from -100 (very negative) to +100 (very positive)
     */
    @Cacheable(value = "gdeltTone", key = "#iso3 + '-' + #days")
    public Double getAverageTone(String iso3, int days) {
        String countryName = MonitoredCountries.getGdeltTerm(iso3);
        log.info("Fetching GDELT tone for {} ({} days)", countryName, days);

        try {
            String query = String.format("country:%s", countryName);

            String url = UriComponentsBuilder.fromHttpUrl(GDELT_DOC_API)
                    .queryParam("query", query)
                    .queryParam("mode", "tonechart")
                    .queryParam("timespan", days + "d")
                    .queryParam("format", "json")
                    .build()
                    .toUriString();

            JsonNode response = callGdeltApiJson(url);

            if (response != null && response.has("tonechart")) {
                JsonNode toneData = response.get("tonechart");
                if (toneData.isArray() && toneData.size() > 0) {
                    // Calculate average tone across time series
                    double sum = 0;
                    int count = 0;
                    for (JsonNode point : toneData) {
                        if (point.has("tone")) {
                            sum += point.get("tone").asDouble();
                            count++;
                        }
                    }
                    if (count > 0) {
                        double avgTone = sum / count;
                        log.info("GDELT: Average tone for {} = {}", countryName, avgTone);
                        return Math.round(avgTone * 100.0) / 100.0;
                    }
                }
            }
            return null;

        } catch (Exception e) {
            log.error("Error fetching GDELT tone for {}: {}", iso3, e.getMessage());
            return null;
        }
    }

    /**
     * Get conflict spike indices for all monitored countries
     * Used for dashboard ranking
     */
    @Cacheable("gdeltAllSpikes")
    public List<MediaSpike> getAllConflictSpikes() {
        log.info("Fetching GDELT spike indices for all monitored countries");

        List<MediaSpike> spikes = new ArrayList<>();

        for (String iso3 : MonitoredCountries.CRISIS_COUNTRIES) {
            try {
                MediaSpike spike = getConflictSpikeIndex(iso3);
                if (spike != null && !"ERROR".equals(spike.getSpikeLevel())) {
                    spikes.add(spike);
                }
                // Rate limiting is handled inside each API call by acquireRateLimit/releaseRateLimit
                // No extra sleep needed here
            } catch (Exception e) {
                log.warn("Error getting spike for {}: {}", iso3, e.getMessage());
            }
        }

        // Sort by composite score: balances anomaly (z-score) with absolute volume
        // This prevents low-volume countries from dominating the ranking
        spikes.sort((a, b) -> {
            double scoreA = compositeRank(a);
            double scoreB = compositeRank(b);
            return Double.compare(scoreB, scoreA);
        });

        log.info("Calculated spike indices for {} countries", spikes.size());
        return spikes;
    }

    /**
     * Composite ranking score for Media Spike alerts.
     * Balances z-score anomaly (40%) with absolute article volume (60%).
     * Prevents low-baseline countries from dominating with inflated z-scores.
     */
    private double compositeRank(MediaSpike spike) {
        double z = spike.getZScore() != null ? spike.getZScore() : 0;
        int articles = spike.getArticlesLast7Days() != null ? spike.getArticlesLast7Days() : 0;

        // Normalize z-score: cap at 5.0 for ranking purposes (beyond 5 = all equally extreme)
        double zNorm = Math.min(z, 5.0) / 5.0; // 0-1

        // Normalize volume: 250 articles = 1.0 (max detectable)
        double volNorm = Math.min(articles, 250.0) / 250.0; // 0-1

        return zNorm * 40 + volNorm * 60;
    }

    /**
     * Get recent conflict events with geographic data
     * For map visualization of hot-spots
     */
    @Cacheable(value = "gdeltGeoEvents", key = "#iso3 + '-' + #days")
    public List<ConflictEvent> getConflictEvents(String iso3, int days) {
        String countryName = MonitoredCountries.getGdeltTerm(iso3);
        log.info("Fetching GDELT geo events for {} ({} days)", countryName, days);

        try {
            String query = String.format("country:%s %s", countryName, CONFLICT_KEYWORDS);

            String url = UriComponentsBuilder.fromHttpUrl(GDELT_GEO_API)
                    .queryParam("query", query)
                    .queryParam("mode", "pointdata")
                    .queryParam("timespan", days + "d")
                    .queryParam("format", "geojson")
                    .build()
                    .toUriString();

            JsonNode response = callGdeltApiJson(url);

            List<ConflictEvent> events = new ArrayList<>();

            if (response != null && response.has("features")) {
                for (JsonNode feature : response.get("features")) {
                    try {
                        JsonNode props = feature.get("properties");
                        JsonNode coords = feature.get("geometry").get("coordinates");

                        if (props != null && coords != null) {
                            events.add(ConflictEvent.builder()
                                    .iso3(iso3)
                                    .title(props.has("name") ? props.get("name").asText() : "Unknown")
                                    .url(props.has("url") ? props.get("url").asText() : null)
                                    .source(props.has("domain") ? props.get("domain").asText() : "GDELT")
                                    .latitude(coords.get(1).asDouble())
                                    .longitude(coords.get(0).asDouble())
                                    .tone(props.has("tone") ? props.get("tone").asDouble() : null)
                                    .eventDate(LocalDate.now()) // GDELT doesn't always provide exact date
                                    .eventType("MEDIA_REPORT")
                                    .build());
                        }
                    } catch (Exception e) {
                        // Skip malformed features
                    }
                }
            }

            log.info("GDELT: Found {} geo events for {}", events.size(), countryName);
            return events;

        } catch (Exception e) {
            log.error("Error fetching GDELT geo events for {}: {}", iso3, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get crisis-related article volume trend
     * Includes humanitarian, disaster, displacement keywords
     */
    @Cacheable(value = "gdeltCrisisVolume", key = "#iso3 + '-' + #days")
    public Map<String, Integer> getCrisisVolumeTrend(String iso3, int days) {
        String countryName = MonitoredCountries.getGdeltTerm(iso3);
        log.info("Fetching GDELT crisis volume trend for {} ({} days)", countryName, days);

        try {
            String query = String.format("country:%s %s", countryName, CRISIS_KEYWORDS);

            String url = UriComponentsBuilder.fromHttpUrl(GDELT_DOC_API)
                    .queryParam("query", query)
                    .queryParam("mode", "timelinevol")
                    .queryParam("timespan", days + "d")
                    .queryParam("format", "json")
                    .build()
                    .toUriString();

            JsonNode response = callGdeltApiJson(url);

            Map<String, Integer> trend = new LinkedHashMap<>();

            if (response != null && response.has("timeline")) {
                JsonNode timeline = response.get("timeline");
                if (timeline.isArray()) {
                    for (JsonNode series : timeline) {
                        if (series.has("data") && series.get("data").isArray()) {
                            for (JsonNode point : series.get("data")) {
                                if (point.has("date") && point.has("value")) {
                                    trend.put(
                                            point.get("date").asText(),
                                            point.get("value").asInt()
                                    );
                                }
                            }
                        }
                    }
                }
            }

            log.info("GDELT: Got {} days of crisis volume trend for {}", trend.size(), countryName);
            return trend;

        } catch (Exception e) {
            log.error("Error fetching GDELT crisis trend for {}: {}", iso3, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Get headlines with URLs for an entire region using a single OR query.
     * Much more efficient than per-country queries (1 call vs N calls).
     *
     * @param region Region code (Africa, MENA, Asia, LAC, Europe)
     * @param topicKeywords Optional topic keywords (e.g., "migration OR refugee"). Null = all topics.
     * @param limit Maximum headlines to return
     */
    @Cacheable(value = "gdeltRegionHeadlines", key = "#region + '-' + (#topicKeywords ?: 'all') + '-' + #limit")
    public List<Headline> getRegionHeadlines(String region, String topicKeywords, int limit) {
        log.info("Fetching GDELT region headlines for {} with topic '{}'", region, topicKeywords);

        // Get top countries for the region (limit to 8 to keep query manageable)
        List<String> regionCountries = getTopCountriesForRegion(region, 8);
        if (regionCountries.isEmpty()) {
            log.warn("No countries found for region: {}", region);
            return Collections.emptyList();
        }

        try {
            // Build OR query with country names
            String countryQuery = regionCountries.stream()
                    .map(iso3 -> "\"" + MonitoredCountries.getGdeltTerm(iso3) + "\"")
                    .collect(Collectors.joining(" OR "));

            // Add topic keywords if specified
            String query;
            if (topicKeywords != null && !topicKeywords.isBlank()) {
                query = String.format("(%s) (%s) sourcelang:english", countryQuery, topicKeywords);
            } else {
                query = String.format("(%s) sourcelang:english", countryQuery);
            }

            String url = UriComponentsBuilder.fromHttpUrl(GDELT_DOC_API)
                    .queryParam("query", query)
                    .queryParam("mode", "artlist")
                    .queryParam("maxrecords", String.valueOf(limit + 10))
                    .queryParam("timespan", "3d")
                    .queryParam("format", "json")
                    .build()
                    .toUriString();

            String responseStr = callGdeltApi(url);

            if (responseStr == null || responseStr.isBlank()) {
                return Collections.emptyList();
            }

            String trimmed = responseStr.trim();
            if (!trimmed.startsWith("{")) {
                return Collections.emptyList();
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode response = mapper.readTree(responseStr);

            List<Headline> headlines = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            if (response != null && response.has("articles")) {
                for (JsonNode article : response.get("articles")) {
                    if (headlines.size() >= limit) break;

                    String title = article.has("title") ? article.get("title").asText() : null;
                    String articleUrl = article.has("url") ? article.get("url").asText() : null;
                    String domain = article.has("domain") ? article.get("domain").asText() : null;

                    if (title != null && !title.isBlank()) {
                        String displayTitle = title.length() > 120 ? title.substring(0, 117) + "..." : title;
                        String key = title.substring(0, Math.min(30, title.length())).toLowerCase();
                        if (!seen.contains(key)) {
                            seen.add(key);
                            headlines.add(Headline.builder()
                                    .title(displayTitle)
                                    .url(articleUrl)
                                    .source(domain)
                                    .build());
                        }
                    }
                }
            }

            log.info("Got {} region headlines for {} (topic: {})", headlines.size(), region, topicKeywords);
            return headlines;

        } catch (Exception e) {
            log.error("Error fetching GDELT region headlines for {}: {}", region, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get top crisis countries for a region, limited to a max count.
     */
    private List<String> getTopCountriesForRegion(String region, int maxCount) {
        if (region == null) return Collections.emptyList();

        String regionLower = region.toLowerCase(java.util.Locale.ROOT);

        // North America: not in CRISIS_COUNTRIES, handle separately
        if (regionLower.equals("north america")) {
            return List.of("USA", "CAN").subList(0, Math.min(2, maxCount));
        }

        List<String> countries = new ArrayList<>();

        for (String iso3 : MonitoredCountries.CRISIS_COUNTRIES) {
            String countryRegion = MonitoredCountries.getRegion(iso3);
            if (countryRegion != null && countryRegion.equalsIgnoreCase(regionLower)) {
                countries.add(iso3);
                if (countries.size() >= maxCount) break;
            }
        }

        return countries;
    }

    /**
     * Get list of monitored countries
     */
    public Set<String> getMonitoredCountries() {
        return new HashSet<>(MonitoredCountries.CRISIS_COUNTRIES);
    }
}
