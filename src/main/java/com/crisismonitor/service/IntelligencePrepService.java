package com.crisismonitor.service;

import com.crisismonitor.config.MonitoredCountries;
import com.crisismonitor.model.Headline;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Intelligence Preparation Service — pre-fetches and enriches news intelligence
 * for all monitored countries. Stores enriched data in-memory so country briefings
 * can access rich article content instead of just headlines.
 *
 * Trigger: manual via API endpoint, or automatic via Cloud Scheduler.
 * Sources: Google News RSS (primary), Bing News RSS (fallback), ReliefWeb API.
 * Storage: ConcurrentHashMap (survives as long as Cloud Run instance is alive).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntelligencePrepService {

    private final ReliefWebService reliefWebService;
    private final ObjectMapper objectMapper;

    private static final int ARTICLES_PER_COUNTRY = 8;
    private static final int SNIPPET_MAX_CHARS = 600;
    private static final int FETCH_TIMEOUT_SECONDS = 8;

    // In-memory store (survives as long as Cloud Run instance is alive)
    private final Map<String, PreparedIntelligence> intelStore = new ConcurrentHashMap<>();

    // Track preparation status
    private final AtomicBoolean preparing = new AtomicBoolean(false);
    private volatile LocalDateTime lastPreparedAt;
    private volatile String lastPrepStatus = "never";
    private final Map<String, String> countryStatus = new ConcurrentHashMap<>();

    private final WebClient rssClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; CrisisMonitor/1.0; +https://crisis-monitor.app)")
            .defaultHeader("Accept", "application/rss+xml, application/xml, text/xml, text/html, */*")
            .build();

    // ========== PUBLIC API ==========

    /**
     * Get preparation status for admin monitoring.
     */
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

    /**
     * Get prepared intelligence for a country (called by ClaudeAnalysisService).
     * Returns null if not available.
     */
    public PreparedIntelligence getIntelligence(String iso3) {
        return intelStore.get(iso3);
    }

    /**
     * Trigger full intelligence preparation for all countries.
     * Runs async — returns immediately with status.
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

        int success = 0;
        int failed = 0;

        // Process in batches of 5 to avoid overwhelming network
        List<String> countries = MonitoredCountries.CRISIS_COUNTRIES;
        for (int i = 0; i < countries.size(); i += 5) {
            List<String> batch = countries.subList(i, Math.min(i + 5, countries.size()));

            List<CompletableFuture<Boolean>> futures = batch.stream()
                    .map(iso3 -> CompletableFuture.supplyAsync(() -> {
                        try {
                            prepareCountry(iso3);
                            countryStatus.put(iso3, "done");
                            return true;
                        } catch (Exception e) {
                            log.warn("Failed to prepare intelligence for {}: {}", iso3, e.getMessage());
                            countryStatus.put(iso3, "failed: " + e.getMessage());
                            return false;
                        }
                    }))
                    .toList();

            // Wait for batch to complete
            for (CompletableFuture<Boolean> f : futures) {
                try {
                    if (f.join()) success++;
                    else failed++;
                } catch (Exception e) {
                    failed++;
                }
            }

            log.info("Intelligence prep progress: {}/{} countries processed",
                    i + batch.size(), countries.size());
        }

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        lastPreparedAt = LocalDateTime.now();
        lastPrepStatus = String.format("completed: %d success, %d failed in %ds", success, failed, elapsed);
        preparing.set(false);

        log.info("=== Intelligence preparation complete: {} success, {} failed in {}s ===",
                success, failed, elapsed);
    }

    // ========== COUNTRY PREPARATION ==========

    private void prepareCountry(String iso3) {
        String countryName = MonitoredCountries.getName(iso3);
        if (countryName == null) return;

        log.debug("Preparing intelligence for {} ({})", countryName, iso3);

        // Fetch news articles with descriptions from Bing RSS
        List<ArticleIntel> articles = fetchNewsWithSnippets(countryName, iso3);

        // Fetch ReliefWeb reports
        List<ArticleIntel> reliefWebArticles = fetchReliefWebIntel(iso3);

        // Combine and store
        PreparedIntelligence intel = new PreparedIntelligence();
        intel.iso3 = iso3;
        intel.countryName = countryName;
        intel.preparedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        intel.newsArticles = articles;
        intel.reliefWebReports = reliefWebArticles;
        intel.articleCount = articles.size() + reliefWebArticles.size();

        intelStore.put(iso3, intel);
        log.debug("Stored intelligence for {} ({} articles)", iso3, intel.articleCount);
    }

    /**
     * Fetch news from Bing RSS — extracts both title and description snippet.
     * Bing RSS descriptions contain article summaries (~200 chars) for free.
     * For top articles, also fetches the actual page content for richer snippets.
     */
    private List<ArticleIntel> fetchNewsWithSnippets(String countryName, String iso3) {
        // Try Google News first (works from Google Cloud), Bing as fallback
        List<ArticleIntel> articles = fetchFromGoogleNews(countryName);
        if (articles.isEmpty()) {
            log.info("Google News returned 0 for {}, trying Bing", countryName);
            articles = fetchFromBingNews(countryName);
        }

        if (articles.isEmpty()) {
            log.warn("No news articles from any source for {} ({})", countryName, iso3);
            return articles;
        }

        log.info("Fetched {} news articles for {} ({})", articles.size(), countryName, iso3);

        // Enrich top 3 articles that lack snippets with page content
        List<CompletableFuture<Void>> fetchFutures = new ArrayList<>();
        for (int i = 0; i < Math.min(3, articles.size()); i++) {
            ArticleIntel article = articles.get(i);
            if (article.snippet == null || article.snippet.length() < 50) {
                fetchFutures.add(CompletableFuture.runAsync(() -> {
                    try {
                        String pageSnippet = fetchPageSnippet(article.url);
                        if (pageSnippet != null) {
                            article.snippet = pageSnippet;
                        }
                    } catch (Exception e) {
                        log.debug("Page fetch failed for {}: {}", article.url, e.getMessage());
                    }
                }));
            }
        }

        if (!fetchFutures.isEmpty()) {
            try {
                CompletableFuture.allOf(fetchFutures.toArray(new CompletableFuture[0]))
                        .get(FETCH_TIMEOUT_SECONDS * 3L, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("Some page fetches timed out for {}", countryName);
            }
        }

        return articles;
    }

    /**
     * Fetch from Google News RSS — works reliably from Google Cloud Run.
     */
    private List<ArticleIntel> fetchFromGoogleNews(String countryName) {
        List<ArticleIntel> articles = new ArrayList<>();
        try {
            String query = URLEncoder.encode(countryName + " crisis humanitarian", StandardCharsets.UTF_8);
            String url = "https://news.google.com/rss/search?q=" + query + "&hl=en&gl=US&ceid=US:en";

            String xml = rssClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (xml == null || xml.isBlank()) {
                log.debug("Google News RSS returned empty for {}", countryName);
                return articles;
            }
            log.debug("Google News RSS response: {} chars for {}", xml.length(), countryName);

            articles = parseRssToArticles(xml, countryName);
        } catch (Exception e) {
            log.warn("Google News RSS failed for {}: {}", countryName, e.getMessage());
        }
        return articles;
    }

    /**
     * Fetch from Bing News RSS — fallback source.
     */
    private List<ArticleIntel> fetchFromBingNews(String countryName) {
        List<ArticleIntel> articles = new ArrayList<>();
        try {
            String query = URLEncoder.encode(countryName + " crisis conflict humanitarian", StandardCharsets.UTF_8);
            String url = "https://www.bing.com/news/search?q=" + query + "&format=rss&mkt=en-US";

            String xml = rssClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (xml == null || xml.isBlank()) {
                log.debug("Bing News RSS returned empty for {}", countryName);
                return articles;
            }
            log.debug("Bing News RSS response: {} chars for {}", xml.length(), countryName);

            articles = parseRssToArticles(xml, countryName);
        } catch (Exception e) {
            log.warn("Bing News RSS failed for {}: {}", countryName, e.getMessage());
        }
        return articles;
    }

    /**
     * Parse RSS XML into ArticleIntel list (works for both Google News and Bing formats).
     */
    private List<ArticleIntel> parseRssToArticles(String xml, String countryName) {
        List<ArticleIntel> articles = new ArrayList<>();
        try {
            XmlMapper xmlMapper = new XmlMapper();
            xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            NewsAggregatorService.RssFeed feed = xmlMapper.readValue(xml, NewsAggregatorService.RssFeed.class);

            if (feed == null || feed.getChannel() == null || feed.getChannel().getItems() == null) {
                log.debug("RSS parse returned no items for {}", countryName);
                return articles;
            }

            Set<String> seen = new HashSet<>();
            for (NewsAggregatorService.RssItem item : feed.getChannel().getItems()) {
                if (articles.size() >= ARTICLES_PER_COUNTRY) break;
                String title = item.getTitle();
                if (title == null || title.isBlank()) continue;

                String key = title.substring(0, Math.min(40, title.length())).toLowerCase();
                if (!seen.add(key)) continue;

                ArticleIntel article = new ArticleIntel();
                article.title = cleanTitle(title);
                article.url = item.getLink();
                article.source = extractSource(title);
                article.date = item.getPubDate();

                // RSS description often contains a useful snippet
                String desc = item.getDescription();
                if (desc != null && !desc.isBlank()) {
                    article.snippet = cleanHtml(desc).trim();
                    if (article.snippet.length() > SNIPPET_MAX_CHARS) {
                        article.snippet = article.snippet.substring(0, SNIPPET_MAX_CHARS) + "...";
                    }
                }
                articles.add(article);
            }
        } catch (Exception e) {
            log.warn("RSS parse failed for {}: {}", countryName, e.getMessage());
        }
        return articles;
    }

    /**
     * Fetch ReliefWeb reports with descriptions.
     */
    private List<ArticleIntel> fetchReliefWebIntel(String iso3) {
        List<ArticleIntel> articles = new ArrayList<>();
        try {
            List<Headline> reports = reliefWebService.getLatestReportsAsHeadlines(iso3, 5, 7);
            if (reports != null) {
                for (Headline h : reports) {
                    ArticleIntel article = new ArticleIntel();
                    article.title = h.getTitle();
                    article.url = h.getUrl();
                    article.source = h.getSource() != null ? h.getSource() : "ReliefWeb";
                    article.date = h.getDate();
                    article.sourceType = "RELIEFWEB";
                    // ReliefWeb headlines are already descriptive, but we could fetch content
                    articles.add(article);
                }
            }
        } catch (Exception e) {
            log.debug("ReliefWeb fetch failed for {}: {}", iso3, e.getMessage());
        }
        return articles;
    }

    /**
     * Fetch actual page content and extract a text snippet.
     * Strips HTML, takes first meaningful paragraph.
     */
    private String fetchPageSnippet(String url) {
        if (url == null || url.isBlank()) return null;

        try {
            String html = rssClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
                    .block();

            if (html == null || html.isBlank()) return null;

            // Extract og:description meta tag first (most reliable)
            String ogDesc = extractMetaContent(html, "og:description");
            if (ogDesc != null && ogDesc.length() > 80) {
                return ogDesc.length() > SNIPPET_MAX_CHARS
                        ? ogDesc.substring(0, SNIPPET_MAX_CHARS) + "..."
                        : ogDesc;
            }

            // Extract meta description
            String metaDesc = extractMetaContent(html, "description");
            if (metaDesc != null && metaDesc.length() > 80) {
                return metaDesc.length() > SNIPPET_MAX_CHARS
                        ? metaDesc.substring(0, SNIPPET_MAX_CHARS) + "..."
                        : metaDesc;
            }

            // Fallback: extract first substantial <p> tag content
            String bodyText = extractFirstParagraphs(html);
            if (bodyText != null && bodyText.length() > 80) {
                return bodyText.length() > SNIPPET_MAX_CHARS
                        ? bodyText.substring(0, SNIPPET_MAX_CHARS) + "..."
                        : bodyText;
            }

        } catch (Exception e) {
            log.debug("Page snippet fetch failed for {}: {}", url, e.getMessage());
        }
        return null;
    }

    // ========== HTML EXTRACTION HELPERS ==========

    private String extractMetaContent(String html, String property) {
        // Match both name="X" and property="X" meta tags
        String[] patterns = {
                "meta[^>]*(?:property|name)=[\"'](?:og:)?" + property + "[\"'][^>]*content=[\"']([^\"']+)[\"']",
                "meta[^>]*content=[\"']([^\"']+)[\"'][^>]*(?:property|name)=[\"'](?:og:)?" + property + "[\"']"
        };
        for (String pattern : patterns) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) {
                return cleanHtml(m.group(1)).trim();
            }
        }
        return null;
    }

    private String extractFirstParagraphs(String html) {
        // Remove script, style, nav, header, footer tags and their content
        String cleaned = html.replaceAll("(?si)<(script|style|nav|header|footer|aside)[^>]*>.*?</\\1>", "");

        // Find <p> tags and extract text
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("<p[^>]*>(.*?)</p>", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(cleaned);

        StringBuilder text = new StringBuilder();
        while (m.find() && text.length() < SNIPPET_MAX_CHARS) {
            String para = cleanHtml(m.group(1)).trim();
            if (para.length() > 40) { // Skip short/navigation paragraphs
                if (text.length() > 0) text.append(" ");
                text.append(para);
            }
        }
        return text.length() > 0 ? text.toString() : null;
    }

    private String cleanHtml(String text) {
        if (text == null) return "";
        return text
                .replaceAll("<[^>]+>", "")           // Strip HTML tags
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&apos;", "'")
                .replaceAll("&#39;", "'")
                .replaceAll("&#x27;", "'")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s+", " ")             // Collapse whitespace
                .trim();
    }

    private String cleanTitle(String title) {
        if (title == null) return "";
        String clean = title.trim();
        int dashIdx = clean.lastIndexOf(" - ");
        if (dashIdx > 0 && dashIdx > clean.length() - 40) {
            clean = clean.substring(0, dashIdx).trim();
        }
        return clean.length() > 150 ? clean.substring(0, 147) + "..." : clean;
    }

    private String extractSource(String title) {
        if (title == null) return "News";
        int dashIdx = title.lastIndexOf(" - ");
        if (dashIdx > 0 && dashIdx > title.length() - 40) {
            return title.substring(dashIdx + 3).trim();
        }
        return "News";
    }

    // ========== DATA MODELS ==========

    /**
     * Prepared intelligence for a single country, stored in-memory.
     */
    public static class PreparedIntelligence {
        public String iso3;
        public String countryName;
        public String preparedAt;
        public int articleCount;
        public List<ArticleIntel> newsArticles = new ArrayList<>();
        public List<ArticleIntel> reliefWebReports = new ArrayList<>();

        // Jackson needs these
        public PreparedIntelligence() {}

        /**
         * Format as text block for injection into Claude data pack.
         */
        public String toDataPackSection() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n### CURRENT INTELLIGENCE (prepared ").append(preparedAt).append(")\n");

            if (!newsArticles.isEmpty()) {
                sb.append("\n**News Analysis:**\n");
                for (int i = 0; i < newsArticles.size(); i++) {
                    ArticleIntel a = newsArticles.get(i);
                    sb.append(String.format("- %s", a.title));
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
                    sb.append(String.format("- %s", a.title));
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

    /**
     * Single article with extracted content snippet.
     */
    public static class ArticleIntel {
        public String title;
        public String url;
        public String source;
        public String date;
        public String snippet;  // Extracted article content (first ~600 chars)
        public String sourceType;

        public ArticleIntel() {}
    }
}
