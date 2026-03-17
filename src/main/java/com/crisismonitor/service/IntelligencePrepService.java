package com.crisismonitor.service;

import com.crisismonitor.config.MonitoredCountries;
import com.crisismonitor.model.Headline;
import com.crisismonitor.model.NewsItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Intelligence Preparation Service — aggregates news and humanitarian data
 * from already-cached sources (StoryService, ReliefWeb, GDELT) into
 * per-country intelligence bundles for Claude briefings.
 *
 * Trigger: manual via API endpoint (should be called AFTER warmup completes).
 * Sources: StoryService news feed (GDELT + RSS), ReliefWeb cached reports.
 * Storage: ConcurrentHashMap (survives as long as Cloud Run instance is alive).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntelligencePrepService {

    private final ReliefWebService reliefWebService;
    private final StoryService storyService;

    private static final int MAX_NEWS_PER_COUNTRY = 8;
    private static final int MAX_RELIEFWEB_PER_COUNTRY = 5;

    // In-memory store (survives as long as Cloud Run instance is alive)
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
     * Uses StoryService news feed (GDELT + RSS) and ReliefWeb — no external
     * RSS calls needed since these are already cached by warmup.
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

        // Step 1: Build country→news index from StoryService news feed (already cached)
        Map<String, List<ArticleIntel>> newsIndex = buildNewsIndex();
        log.info("Built news index: {} countries with articles", newsIndex.size());

        int success = 0;
        int failed = 0;

        for (String iso3 : MonitoredCountries.CRISIS_COUNTRIES) {
            try {
                prepareCountry(iso3, newsIndex);
                countryStatus.put(iso3, "done");
                success++;
            } catch (Exception e) {
                log.warn("Failed to prepare intelligence for {}: {}", iso3, e.getMessage());
                countryStatus.put(iso3, "failed: " + e.getMessage());
                failed++;
            }
        }

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        lastPreparedAt = LocalDateTime.now();
        lastPrepStatus = String.format("completed: %d success, %d failed in %ds", success, failed, elapsed);
        preparing.set(false);

        // Log summary
        int totalArticles = intelStore.values().stream().mapToInt(i -> i.articleCount).sum();
        log.info("=== Intelligence preparation complete: {} success, {} failed in {}s ({} total articles) ===",
                success, failed, elapsed, totalArticles);
    }

    // ========== INTERNAL ==========

    /**
     * Build country→articles index from StoryService news feed cache.
     * The news feed contains GDELT + RSS articles tagged with country ISO3.
     */
    private Map<String, List<ArticleIntel>> buildNewsIndex() {
        Map<String, List<ArticleIntel>> index = new HashMap<>();

        try {
            // Get the global news feed (cached by warmup)
            StoryService.NewsFeedData feed = storyService.getNewsFeed(null, null);
            if (feed == null) {
                log.warn("News feed not available — warmup may not have completed");
                return index;
            }

            // Index media articles (GDELT + RSS) by country
            if (feed.getMedia() != null) {
                for (NewsItem item : feed.getMedia()) {
                    String iso3 = item.getCountry();
                    if (iso3 == null || iso3.isBlank()) continue;
                    iso3 = iso3.toUpperCase();

                    index.computeIfAbsent(iso3, k -> new ArrayList<>()).add(toArticleIntel(item));
                }
                log.info("Indexed {} media articles across {} countries",
                        feed.getMedia().size(),
                        index.size());
            }

            // Also index ReliefWeb articles by country
            if (feed.getReliefweb() != null) {
                for (NewsItem item : feed.getReliefweb()) {
                    String iso3 = item.getCountry();
                    if (iso3 == null || iso3.isBlank()) continue;
                    iso3 = iso3.toUpperCase();

                    // Store under separate key to distinguish later
                    index.computeIfAbsent(iso3 + "_RW", k -> new ArrayList<>()).add(toArticleIntel(item));
                }
            }

        } catch (Exception e) {
            log.error("Failed to build news index from StoryService: {}", e.getMessage());
        }

        // Also try per-region feeds for broader coverage
        for (String region : List.of("africa", "mena", "asia", "lac", "europe")) {
            try {
                StoryService.NewsFeedData regionFeed = storyService.getNewsFeed(region, null);
                if (regionFeed != null && regionFeed.getMedia() != null) {
                    for (NewsItem item : regionFeed.getMedia()) {
                        String iso3 = item.getCountry();
                        if (iso3 == null || iso3.isBlank()) continue;
                        iso3 = iso3.toUpperCase();

                        List<ArticleIntel> existing = index.computeIfAbsent(iso3, k -> new ArrayList<>());
                        // Only add if we don't have too many already
                        if (existing.size() < MAX_NEWS_PER_COUNTRY * 2) {
                            existing.add(toArticleIntel(item));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Region feed {} not available: {}", region, e.getMessage());
            }
        }

        return index;
    }

    private void prepareCountry(String iso3, Map<String, List<ArticleIntel>> newsIndex) {
        String countryName = MonitoredCountries.getName(iso3);

        // Get news from pre-built index
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

        // Get ReliefWeb from index or fetch directly (cached)
        List<ArticleIntel> reliefWeb = newsIndex.getOrDefault(iso3 + "_RW", Collections.emptyList());
        if (reliefWeb.isEmpty()) {
            reliefWeb = fetchReliefWebIntel(iso3);
        }

        // Deduplicate ReliefWeb
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

        if (intel.articleCount > 0) {
            log.debug("Prepared {} for {} ({} news + {} reliefweb)",
                    intel.articleCount, iso3, dedupedNews.size(), dedupedRW.size());
        }
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
     * Fetch ReliefWeb reports directly (uses cached data via @Cacheable).
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
