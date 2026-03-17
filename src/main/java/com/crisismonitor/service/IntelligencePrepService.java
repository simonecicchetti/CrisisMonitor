package com.crisismonitor.service;

import com.crisismonitor.config.MonitoredCountries;
import com.crisismonitor.model.Headline;
import com.crisismonitor.model.NewsItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Intelligence Preparation Service — aggregates humanitarian reports and news
 * from already-cached sources into per-country intelligence bundles for Claude briefings.
 *
 * Strategy: uses ONLY data that warmup has already cached (ReliefWeb, news feed).
 * Never triggers new GDELT/RSS fetches — avoids rate limit contention with warmup.
 *
 * Trigger: manual via API endpoint (should be called AFTER warmup completes).
 * Storage: ConcurrentHashMap (survives as long as Cloud Run instance is alive).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntelligencePrepService {

    private final ReliefWebService reliefWebService;
    private final CacheManager cacheManager;

    private static final int MAX_NEWS_PER_COUNTRY = 8;
    private static final int MAX_RELIEFWEB_PER_COUNTRY = 5;

    // In-memory store
    private final Map<String, PreparedIntelligence> intelStore = new ConcurrentHashMap<>();

    // Track preparation status
    private final AtomicBoolean preparing = new AtomicBoolean(false);
    private volatile LocalDateTime lastPreparedAt;
    private volatile String lastPrepStatus = "never";
    private final Map<String, String> countryStatus = new ConcurrentHashMap<>();

    // ========== PUBLIC API ==========

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("preparing", preparing.get());
        status.put("lastPreparedAt", lastPreparedAt != null ? lastPreparedAt.toString() : "never");
        status.put("lastStatus", lastPrepStatus);
        status.put("countriesPrepared", countryStatus.entrySet().stream()
                .filter(e -> "done".equals(e.getValue()))
                .count());
        status.put("countriesTotal", MonitoredCountries.CRISIS_COUNTRIES.size());
        return status;
    }

    public PreparedIntelligence getIntelligence(String iso3) {
        return intelStore.get(iso3);
    }

    /**
     * Aggregate intelligence from cached data sources for all countries.
     * Uses cached ReliefWeb reports and cached news feed — no new external calls.
     * Should complete in seconds since all data is already in cache.
     */
    @Async
    public void prepareAll() {
        if (!preparing.compareAndSet(false, true)) {
            log.warn("Intelligence preparation already in progress, skipping");
            return;
        }

        log.info("=== Starting intelligence preparation for {} countries ===",
                MonitoredCountries.CRISIS_COUNTRIES.size());
        long startTime = System.currentTimeMillis();
        lastPrepStatus = "running";
        countryStatus.clear();

        // Step 1: Try to get news index from already-cached news feed
        Map<String, List<ArticleIntel>> newsIndex = buildNewsIndexFromCache();

        int success = 0;
        int failed = 0;
        int withArticles = 0;

        for (String iso3 : MonitoredCountries.CRISIS_COUNTRIES) {
            try {
                prepareCountry(iso3, newsIndex);
                countryStatus.put(iso3, "done");
                success++;
                PreparedIntelligence intel = intelStore.get(iso3);
                if (intel != null && intel.articleCount > 0) withArticles++;
            } catch (Exception e) {
                log.warn("Failed to prepare intelligence for {}: {}", iso3, e.getMessage());
                countryStatus.put(iso3, "failed: " + e.getMessage());
                failed++;
            }
        }

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        lastPreparedAt = LocalDateTime.now();
        lastPrepStatus = String.format("completed: %d success (%d with articles), %d failed in %ds",
                success, withArticles, failed, elapsed);
        preparing.set(false);

        log.info("=== Intelligence preparation complete: {} success ({} with articles), {} failed in {}s ===",
                success, withArticles, failed, elapsed);
    }

    // ========== INTERNAL ==========

    /**
     * Build country→articles index from the already-cached news feed.
     * Reads directly from Spring cache — if not cached, returns empty (no trigger).
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<ArticleIntel>> buildNewsIndexFromCache() {
        Map<String, List<ArticleIntel>> index = new HashMap<>();

        try {
            // Check if news feed is already in cache (don't trigger computation)
            Cache newsFeedCache = cacheManager.getCache("newsFeed");
            if (newsFeedCache == null) {
                log.info("News feed cache not available");
                return index;
            }

            // The cache key for getNewsFeed(null, null) is "-" (empty region + empty topic)
            Cache.ValueWrapper cached = newsFeedCache.get("-");
            if (cached == null) {
                log.info("News feed not yet cached — will rely on ReliefWeb only");
                return index;
            }

            StoryService.NewsFeedData feed = (StoryService.NewsFeedData) cached.get();
            if (feed == null) return index;

            // Index media articles by country
            if (feed.getMedia() != null) {
                for (NewsItem item : feed.getMedia()) {
                    String iso3 = item.getCountry();
                    if (iso3 == null || iso3.isBlank()) continue;
                    iso3 = iso3.toUpperCase();
                    index.computeIfAbsent(iso3, k -> new ArrayList<>()).add(toArticleIntel(item));
                }
            }

            // Index ReliefWeb articles from feed
            if (feed.getReliefweb() != null) {
                for (NewsItem item : feed.getReliefweb()) {
                    String iso3 = item.getCountry();
                    if (iso3 == null || iso3.isBlank()) continue;
                    iso3 = iso3.toUpperCase();
                    index.computeIfAbsent(iso3 + "_RW", k -> new ArrayList<>()).add(toArticleIntel(item));
                }
            }

            long mediaCountries = index.entrySet().stream().filter(e -> !e.getKey().endsWith("_RW")).count();
            long rwCountries = index.entrySet().stream().filter(e -> e.getKey().endsWith("_RW")).count();
            log.info("News index from cache: {} media countries, {} reliefweb countries", mediaCountries, rwCountries);

        } catch (Exception e) {
            log.warn("Failed to read news feed from cache: {}", e.getMessage());
        }

        return index;
    }

    private void prepareCountry(String iso3, Map<String, List<ArticleIntel>> newsIndex) {
        String countryName = MonitoredCountries.getName(iso3);

        // Get news from pre-built index (from cached news feed)
        List<ArticleIntel> news = newsIndex.getOrDefault(iso3, Collections.emptyList());

        // Deduplicate by title prefix
        Set<String> seen = new HashSet<>();
        List<ArticleIntel> dedupedNews = new ArrayList<>();
        for (ArticleIntel a : news) {
            if (a.title == null) continue;
            String key = a.title.substring(0, Math.min(40, a.title.length())).toLowerCase();
            if (seen.add(key) && dedupedNews.size() < MAX_NEWS_PER_COUNTRY) {
                dedupedNews.add(a);
            }
        }

        // Get ReliefWeb — from index first, then try direct (cached by warmup)
        List<ArticleIntel> reliefWeb = newsIndex.getOrDefault(iso3 + "_RW", Collections.emptyList());
        if (reliefWeb.isEmpty()) {
            reliefWeb = fetchReliefWebIntel(iso3);
        }

        List<ArticleIntel> dedupedRW = new ArrayList<>();
        for (ArticleIntel a : reliefWeb) {
            if (a.title == null) continue;
            String key = a.title.substring(0, Math.min(40, a.title.length())).toLowerCase();
            if (seen.add(key) && dedupedRW.size() < MAX_RELIEFWEB_PER_COUNTRY) {
                dedupedRW.add(a);
            }
        }

        PreparedIntelligence intel = new PreparedIntelligence();
        intel.iso3 = iso3;
        intel.countryName = countryName;
        intel.preparedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        intel.newsArticles = dedupedNews;
        intel.reliefWebReports = dedupedRW;
        intel.articleCount = dedupedNews.size() + dedupedRW.size();

        intelStore.put(iso3, intel);
    }

    private ArticleIntel toArticleIntel(NewsItem item) {
        ArticleIntel a = new ArticleIntel();
        a.title = item.getTitle();
        a.url = item.getUrl();
        a.source = item.getSource();
        a.date = item.getTimeAgo();
        a.sourceType = item.getSourceType();
        return a;
    }

    /**
     * Fetch ReliefWeb reports — uses @Cacheable so it reads from cache if available.
     */
    private List<ArticleIntel> fetchReliefWebIntel(String iso3) {
        List<ArticleIntel> articles = new ArrayList<>();
        try {
            List<Headline> reports = reliefWebService.getLatestReportsAsHeadlines(iso3, MAX_RELIEFWEB_PER_COUNTRY, 7);
            if (reports != null) {
                for (Headline h : reports) {
                    ArticleIntel article = new ArticleIntel();
                    article.title = h.getTitle();
                    article.url = h.getUrl();
                    article.source = h.getSource() != null ? h.getSource() : "ReliefWeb";
                    article.date = h.getDate();
                    article.sourceType = "RELIEFWEB";
                    articles.add(article);
                }
            }
        } catch (Exception e) {
            log.debug("ReliefWeb fetch failed for {}: {}", iso3, e.getMessage());
        }
        return articles;
    }

    // ========== DATA MODELS ==========

    public static class PreparedIntelligence {
        public String iso3;
        public String countryName;
        public String preparedAt;
        public int articleCount;
        public List<ArticleIntel> newsArticles = new ArrayList<>();
        public List<ArticleIntel> reliefWebReports = new ArrayList<>();

        public PreparedIntelligence() {}

        public String toDataPackSection() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n### CURRENT INTELLIGENCE (prepared ").append(preparedAt).append(")\n");

            if (!newsArticles.isEmpty()) {
                sb.append("\n**News Analysis:**\n");
                for (ArticleIntel a : newsArticles) {
                    sb.append("- ").append(a.title);
                    if (a.source != null) sb.append(" [").append(a.source).append("]");
                    sb.append("\n");
                    if (a.snippet != null && !a.snippet.isBlank()) {
                        sb.append("  ").append(a.snippet).append("\n");
                    }
                }
            }

            if (!reliefWebReports.isEmpty()) {
                sb.append("\n**Humanitarian Reports:**\n");
                for (ArticleIntel a : reliefWebReports) {
                    sb.append("- ").append(a.title);
                    if (a.source != null) sb.append(" [").append(a.source).append("]");
                    sb.append("\n");
                    if (a.snippet != null && !a.snippet.isBlank()) {
                        sb.append("  ").append(a.snippet).append("\n");
                    }
                }
            }

            return sb.toString();
        }
    }

    public static class ArticleIntel {
        public String title;
        public String url;
        public String source;
        public String date;
        public String snippet;
        public String sourceType;

        public ArticleIntel() {}
    }
}
