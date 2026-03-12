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

    // Global rate limiter - GDELT requires at least 15 seconds between requests
    // (stricter than documented 5 seconds based on 429 errors, IP may be flagged)
    private static final long RATE_LIMIT_MS = 15000; // 15 seconds to be safe
    private static final AtomicLong lastRequestTime = new AtomicLong(0);

    public GDELTService() {
        this.gdeltClient = WebClient.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    // Lock object for thread-safe rate limiting
    private static final Object RATE_LIMIT_LOCK = new Object();

    /**
     * Rate limiter - ensures minimum 15 seconds between any GDELT API call.
     * Thread-safe using synchronized block to prevent concurrent requests.
     */
    private void waitForRateLimit() {
        synchronized (RATE_LIMIT_LOCK) {
            long now = System.currentTimeMillis();
            long lastRequest = lastRequestTime.get();
            long elapsed = now - lastRequest;

            if (elapsed < RATE_LIMIT_MS) {
                long waitTime = RATE_LIMIT_MS - elapsed;
                log.debug("GDELT rate limit: waiting {}ms", waitTime);
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            lastRequestTime.set(System.currentTimeMillis());
        }
    }

    /**
     * Get conflict article count for a country over specified days
     * Used for trend calculation and spike detection
     */
    @Cacheable(value = "gdeltConflictCount", key = "#iso3 + '-' + #days")
    public int getConflictArticleCount(String iso3, int days) {
        String countryName = MonitoredCountries.getGdeltTerm(iso3);
        log.info("Fetching GDELT conflict count for {} ({} days)", countryName, days);

        try {
            // GDELT query: simple country + conflict query
            // Keep it simple - GDELT's OR syntax is finicky
            String query = String.format("\"%s\" conflict", countryName);

            String url = UriComponentsBuilder.fromHttpUrl(GDELT_DOC_API)
                    .queryParam("query", query)
                    .queryParam("mode", "artlist")
                    .queryParam("maxrecords", "250")
                    .queryParam("timespan", days + "d")
                    .queryParam("format", "json")
                    .build()
                    .toUriString();

            // Rate limit before API call
            waitForRateLimit();

            // Get as String first to handle various response formats
            String responseStr = gdeltClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseStr == null || responseStr.isBlank()) {
                log.debug("GDELT: Empty response for {}", countryName);
                return 0;
            }

            // Parse JSON - GDELT sometimes returns non-JSON for empty results
            String trimmed = responseStr.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                // Log first 200 chars to understand the response
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
            // Get article counts for different periods
            int count7d = getConflictArticleCount(iso3, 7);
            int count30d = getConflictArticleCount(iso3, 30);

            // Calculate daily averages
            double avg7d = count7d / 7.0;
            double avg30d = count30d / 30.0;

            // Calculate z-score (simplified - assumes baseline SD ~ 0.3 * mean)
            double baselineStd = Math.max(avg30d * 0.3, 1.0); // Prevent division by zero
            double zScore = (avg7d - avg30d) / baselineStd;

            // Determine spike level
            String level;
            if (zScore > 3.0) level = "CRITICAL";
            else if (zScore > 2.0) level = "HIGH";
            else if (zScore > 1.0) level = "ELEVATED";
            else if (zScore > 0.5) level = "MODERATE";
            else level = "NORMAL";

            // Get top headlines for context (only for elevated spikes)
            List<String> headlines = null;
            if (zScore > 0.5) {
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
            String query = String.format("\"%s\" %s sourcelang:english", countryName, keywords);

            String url = UriComponentsBuilder.fromHttpUrl(GDELT_DOC_API)
                    .queryParam("query", query)
                    .queryParam("mode", "artlist")
                    .queryParam("maxrecords", String.valueOf(limit + 5)) // Get extra to filter duplicates
                    .queryParam("timespan", timespan)
                    .queryParam("format", "json")
                    .build()
                    .toUriString();

            waitForRateLimit();

            String responseStr = gdeltClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

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
            String query = String.format("\"%s\" %s sourcelang:english", countryName, keywords);

            String url = UriComponentsBuilder.fromHttpUrl(GDELT_DOC_API)
                    .queryParam("query", query)
                    .queryParam("mode", "artlist")
                    .queryParam("maxrecords", String.valueOf(limit + 10))
                    .queryParam("timespan", timespan)
                    .queryParam("format", "json")
                    .build()
                    .toUriString();

            // Rate limit before API call
            waitForRateLimit();

            String responseStr = gdeltClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

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

            // Rate limit before API call
            waitForRateLimit();

            JsonNode response = gdeltClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

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
                // Rate limiting is handled inside each API call by waitForRateLimit()
                // No extra sleep needed here
            } catch (Exception e) {
                log.warn("Error getting spike for {}: {}", iso3, e.getMessage());
            }
        }

        // Sort by z-score descending (highest spikes first)
        spikes.sort((a, b) -> Double.compare(
                b.getZScore() != null ? b.getZScore() : 0,
                a.getZScore() != null ? a.getZScore() : 0
        ));

        log.info("Calculated spike indices for {} countries", spikes.size());
        return spikes;
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

            // Rate limit before API call
            waitForRateLimit();

            JsonNode response = gdeltClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

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

            // Rate limit before API call
            waitForRateLimit();

            JsonNode response = gdeltClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

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

            waitForRateLimit();

            String responseStr = gdeltClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

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
