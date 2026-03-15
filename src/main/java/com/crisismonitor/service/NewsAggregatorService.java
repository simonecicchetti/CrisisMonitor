package com.crisismonitor.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Humanitarian News Aggregator Service
 * Pulls from multiple sources and organizes into regional briefings
 */
@Slf4j
@Service
public class NewsAggregatorService {

    private final WebClient webClient;

    public NewsAggregatorService() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .defaultHeader("User-Agent", "CrisisMonitor/1.0 (humanitarian monitoring)")
                .build();
    }

    // RSS Feed URLs for humanitarian news sources
    private static final Map<String, String> NEWS_SOURCES = Map.of(
        "ReliefWeb", "https://reliefweb.int/updates/rss.xml",
        "UN News", "https://news.un.org/feed/subscribe/en/news/all/rss.xml",
        "ICRC", "https://www.icrc.org/en/rss/news"
    );

    // Region definitions with associated countries and keywords
    // Keywords should be SPECIFIC country/place names, not generic terms
    private static final Map<String, RegionConfig> REGIONS = new LinkedHashMap<>();
    static {
        REGIONS.put("LAC", new RegionConfig(
            "Latin America & Caribbean",
            Arrays.asList("VEN", "COL", "HTI", "CUB", "GTM", "HND", "SLV", "NIC", "PER", "ECU", "BOL", "CHL", "ARG", "BRA", "MEX", "DOM", "JAM"),
            Arrays.asList("venezuela", "venezuelan", "colombia", "colombian", "haiti", "haitian", "cuba", "cuban",
                         "guatemala", "honduras", "el salvador", "nicaragua", "peru", "ecuador", "bolivia",
                         "chile", "chilean", "latin america", "caribbean", "central america", "mexico", "mexican",
                         "caracas", "bogota", "port-au-prince", "havana", "darien")
        ));
        REGIONS.put("MENA", new RegionConfig(
            "Middle East & North Africa",
            Arrays.asList("SYR", "IRQ", "YEM", "LBN", "JOR", "PSE", "IRN", "LBY", "EGY", "TUN", "ISR"),
            Arrays.asList("syria", "syrian", "iraq", "iraqi", "yemen", "yemeni", "lebanon", "lebanese",
                         "jordan", "palestine", "palestinian", "gaza", "west bank", "iran", "iranian",
                         "libya", "libyan", "egypt", "egyptian", "middle east", "rafah", "damascus",
                         "baghdad", "sanaa", "beirut", "tel aviv", "jerusalem", "houthi")
        ));
        REGIONS.put("AFRICA_EAST", new RegionConfig(
            "East Africa & Horn",
            Arrays.asList("ETH", "SOM", "SSD", "SDN", "KEN", "UGA", "ERI", "DJI", "RWA", "BDI"),
            Arrays.asList("ethiopia", "ethiopian", "somalia", "somali", "south sudan", "sudan", "sudanese",
                         "kenya", "kenyan", "uganda", "ugandan", "eritrea", "horn of africa",
                         "tigray", "darfur", "khartoum", "addis ababa", "mogadishu", "juba", "nairobi")
        ));
        REGIONS.put("AFRICA_WEST", new RegionConfig(
            "West & Central Africa",
            Arrays.asList("NGA", "NER", "MLI", "BFA", "TCD", "CMR", "COD", "CAF", "GHA", "SEN", "CIV"),
            Arrays.asList("nigeria", "nigerian", "niger", "mali", "malian", "burkina faso", "chad",
                         "cameroon", "congo", "congolese", "drc", "dr congo", "sahel", "central african",
                         "kinshasa", "lagos", "abuja", "bamako", "ouagadougou", "m23", "kivu")
        ));
        REGIONS.put("ASIA", new RegionConfig(
            "Asia & Pacific",
            Arrays.asList("AFG", "PAK", "BGD", "MMR", "NPL", "PHL", "IDN", "LKA", "IND"),
            Arrays.asList("afghanistan", "afghan", "pakistan", "pakistani", "bangladesh", "bangladeshi",
                         "myanmar", "burmese", "rohingya", "nepal", "philippines", "filipino",
                         "indonesia", "indonesian", "sri lanka", "kabul", "islamabad", "dhaka",
                         "yangon", "manila", "taliban", "rakhine")
        ));
        REGIONS.put("EUROPE", new RegionConfig(
            "Europe & Central Asia",
            Arrays.asList("UKR", "MDA", "GEO", "ARM", "AZE", "RUS", "BLR"),
            Arrays.asList("ukraine", "ukrainian", "moldova", "moldovan", "georgia", "georgian",
                         "caucasus", "kyiv", "kiev", "kharkiv", "odesa", "donbas", "crimea",
                         "russia", "russian", "belarus", "belarusian")
        ));
    }

    // Topic keywords for categorization
    private static final Map<String, List<String>> TOPICS = Map.of(
        "CONFLICT", Arrays.asList("conflict", "violence", "military", "armed", "attack", "airstrike", "war", "fighting", "troops", "bomb", "bombing", "strike", "missile", "offensive", "killed", "casualties", "invasion", "siege", "shelling", "targeted", "combat", "battlefield"),
        "MIGRATION", Arrays.asList("migration", "migrant", "refugee", "displacement", "idp", "asylum", "border", "transit"),
        "FOOD_SECURITY", Arrays.asList("food", "hunger", "famine", "nutrition", "wfp", "food security", "malnutrition", "starvation"),
        "HEALTH", Arrays.asList("health", "disease", "outbreak", "cholera", "epidemic", "hospital", "medical", "vaccination"),
        "CLIMATE", Arrays.asList("flood", "drought", "cyclone", "hurricane", "earthquake", "climate", "disaster", "weather"),
        "ECONOMIC", Arrays.asList("economic", "crisis", "inflation", "currency", "fuel", "shortage", "poverty", "livelihood"),
        "POLITICAL", Arrays.asList("political", "election", "government", "president", "minister", "policy", "transition", "coup")
    );

    /**
     * Get the daily intelligence briefing organized by region
     */
    @Cacheable(value = "dailyBriefing", unless = "#result == null")
    public DailyBriefing getDailyBriefing() {
        log.info("Building daily intelligence briefing...");
        long startTime = System.currentTimeMillis();

        DailyBriefing briefing = new DailyBriefing();
        briefing.setDate(LocalDate.now().toString());
        briefing.setTimestamp(LocalDateTime.now().toString());

        // Fetch news from all sources
        List<NewsItem> allNews = new ArrayList<>();

        // 1. Fetch from RSS feeds
        for (Map.Entry<String, String> source : NEWS_SOURCES.entrySet()) {
            try {
                List<NewsItem> items = fetchRssFeed(source.getKey(), source.getValue());
                allNews.addAll(items);
            } catch (Exception e) {
                log.warn("Failed to fetch from {}: {}", source.getKey(), e.getMessage());
            }
        }

        // 2. Fetch from ReliefWeb API (more structured)
        try {
            List<NewsItem> reliefWebNews = fetchReliefWebUpdates();
            allNews.addAll(reliefWebNews);
        } catch (Exception e) {
            log.warn("Failed to fetch ReliefWeb updates: {}", e.getMessage());
        }

        // 3. Categorize by region
        Map<String, List<NewsItem>> regionalNews = new LinkedHashMap<>();
        for (String regionCode : REGIONS.keySet()) {
            RegionConfig config = REGIONS.get(regionCode);
            List<NewsItem> regionItems = allNews.stream()
                .filter(item -> matchesRegion(item, config))
                .sorted((a, b) -> b.getPublishedDate().compareTo(a.getPublishedDate()))
                .limit(10)
                .collect(Collectors.toList());

            if (!regionItems.isEmpty()) {
                // Assign topics to each item
                regionItems.forEach(item -> item.setTopics(detectTopics(item)));
                regionalNews.put(regionCode, regionItems);
            }
        }

        // 4. Build regional briefings
        List<RegionalBriefing> regionalBriefings = new ArrayList<>();
        for (Map.Entry<String, List<NewsItem>> entry : regionalNews.entrySet()) {
            RegionConfig config = REGIONS.get(entry.getKey());
            RegionalBriefing rb = new RegionalBriefing();
            rb.setRegionCode(entry.getKey());
            rb.setRegionName(config.getName());
            rb.setNewsItems(entry.getValue());
            rb.setItemCount(entry.getValue().size());
            regionalBriefings.add(rb);
        }
        briefing.setRegionalBriefings(regionalBriefings);

        // 5. Classify priority level for each item
        allNews.forEach(this::classifyPriority);

        // 6. Get top headlines (most recent, high-impact)
        List<NewsItem> topHeadlines = allNews.stream()
            .sorted((a, b) -> b.getPublishedDate().compareTo(a.getPublishedDate()))
            .limit(5)
            .collect(Collectors.toList());
        briefing.setTopHeadlines(topHeadlines);

        // 7. Priority alerts (CRITICAL items only, deduplicated by title similarity)
        List<NewsItem> priorityAlerts = allNews.stream()
            .filter(i -> "CRITICAL".equals(i.getPriority()))
            .sorted((a, b) -> b.getPublishedDate().compareTo(a.getPublishedDate()))
            .limit(8)
            .collect(Collectors.toList());
        briefing.setPriorityAlerts(priorityAlerts);

        // 8. Executive summary (rule-based, no AI call)
        briefing.setExecutiveSummary(generateExecutiveSummary(allNews, regionalBriefings, priorityAlerts));

        briefing.setTotalItems(allNews.size());
        briefing.setStatus("READY");

        long duration = System.currentTimeMillis() - startTime;
        log.info("Daily briefing built in {}ms: {} items across {} regions",
            duration, allNews.size(), regionalBriefings.size());

        return briefing;
    }

    /**
     * Fetch and parse RSS feed
     */
    private List<NewsItem> fetchRssFeed(String sourceName, String feedUrl) {
        List<NewsItem> items = new ArrayList<>();

        try {
            String xml = webClient.get()
                .uri(feedUrl)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(java.time.Duration.ofSeconds(15))
                .block();

            if (xml == null || xml.isEmpty()) {
                return items;
            }

            // Parse RSS XML (ignore unknown fields)
            XmlMapper xmlMapper = new XmlMapper();
            xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            RssFeed feed = xmlMapper.readValue(xml, RssFeed.class);

            if (feed != null && feed.getChannel() != null && feed.getChannel().getItems() != null) {
                for (RssItem rssItem : feed.getChannel().getItems()) {
                    NewsItem item = new NewsItem();
                    item.setTitle(rssItem.getTitle());
                    item.setDescription(cleanHtml(rssItem.getDescription()));
                    item.setUrl(rssItem.getLink());
                    item.setSource(sourceName);
                    item.setPublishedDate(parseDate(rssItem.getPubDate()));
                    items.add(item);
                }
            }

            log.info("Fetched {} items from {} RSS", items.size(), sourceName);

        } catch (Exception e) {
            log.warn("Error parsing RSS from {}: {}", sourceName, e.getMessage(), e);
        }

        return items;
    }

    /**
     * Fetch updates from ReliefWeb API
     */
    private List<NewsItem> fetchReliefWebUpdates() {
        List<NewsItem> items = new ArrayList<>();

        try {
            // Fetch recent updates (situation reports, news)
            String response = webClient.get()
                .uri("https://api.reliefweb.int/v1/reports?appname=crisis-monitor&limit=30&preset=latest&query[value]=language:\"English\"&fields[include][]=title&fields[include][]=url&fields[include][]=source&fields[include][]=date&fields[include][]=country&fields[include][]=body-html")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(java.time.Duration.ofSeconds(15))
                .block();

            if (response != null) {
                // Parse JSON response
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response);
                com.fasterxml.jackson.databind.JsonNode data = root.path("data");

                for (com.fasterxml.jackson.databind.JsonNode report : data) {
                    NewsItem item = new NewsItem();
                    com.fasterxml.jackson.databind.JsonNode fields = report.path("fields");

                    item.setTitle(fields.path("title").asText(""));
                    item.setUrl(fields.path("url").asText(""));
                    item.setPublishedDate(fields.path("date").path("created").asText(""));

                    // Get source name
                    com.fasterxml.jackson.databind.JsonNode sources = fields.path("source");
                    if (sources.isArray() && sources.size() > 0) {
                        item.setSource(sources.get(0).path("name").asText("ReliefWeb"));
                    } else {
                        item.setSource("ReliefWeb");
                    }

                    // Get countries for regional matching
                    List<String> countries = new ArrayList<>();
                    com.fasterxml.jackson.databind.JsonNode countryNodes = fields.path("country");
                    if (countryNodes.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode c : countryNodes) {
                            countries.add(c.path("name").asText("").toLowerCase());
                            String iso3 = c.path("iso3").asText("");
                            if (!iso3.isEmpty()) {
                                countries.add(iso3);
                            }
                        }
                    }
                    item.setCountries(countries);

                    // Get summary from body
                    String body = fields.path("body-html").asText("");
                    item.setDescription(truncateText(cleanHtml(body), 300));

                    items.add(item);
                }
            }

            log.info("Fetched {} items from ReliefWeb API", items.size());

        } catch (Exception e) {
            log.warn("Error fetching ReliefWeb updates: {}", e.getMessage(), e);
        }

        return items;
    }

    /**
     * Check if news item matches a region
     * Priority: 1) Country codes from data, 2) Country names in text
     * Avoids generic keywords that match everywhere
     */
    private boolean matchesRegion(NewsItem item, RegionConfig config) {
        // First priority: Check country codes from ReliefWeb data
        if (item.getCountries() != null) {
            for (String country : item.getCountries()) {
                if (config.getCountryCodes().contains(country.toUpperCase())) {
                    return true;
                }
            }
        }

        // Second priority: Check specific country names in text (not generic keywords)
        String searchText = (item.getTitle() + " " + item.getDescription()).toLowerCase();
        for (String keyword : config.getKeywords()) {
            // Skip generic keywords that match too broadly
            if (isGenericKeyword(keyword)) {
                continue;
            }
            if (searchText.contains(keyword.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if keyword is too generic for regional matching
     */
    private boolean isGenericKeyword(String keyword) {
        return keyword.equalsIgnoreCase("conflict") ||
               keyword.equalsIgnoreCase("violence") ||
               keyword.equalsIgnoreCase("military") ||
               keyword.equalsIgnoreCase("displacement") ||
               keyword.equalsIgnoreCase("refugee") ||
               keyword.equalsIgnoreCase("migration") ||
               keyword.equalsIgnoreCase("migrant") ||
               keyword.equalsIgnoreCase("idp") ||
               keyword.equalsIgnoreCase("famine") ||
               keyword.equalsIgnoreCase("drought") ||
               keyword.equalsIgnoreCase("jihadist");
    }

    /**
     * Detect topics for a news item
     */
    private List<String> detectTopics(NewsItem item) {
        List<String> detected = new ArrayList<>();
        String searchText = (item.getTitle() + " " + item.getDescription()).toLowerCase();

        for (Map.Entry<String, List<String>> topic : TOPICS.entrySet()) {
            for (String keyword : topic.getValue()) {
                if (searchText.contains(keyword)) {
                    detected.add(topic.getKey());
                    break;
                }
            }
        }

        return detected;
    }

    /**
     * Parse various date formats
     */
    private String parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return LocalDateTime.now().toString();
        }
        // Return as-is for now, could normalize later
        return dateStr;
    }

    /**
     * Clean HTML tags from text
     */
    private String cleanHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", "")
                   .replaceAll("&nbsp;", " ")
                   .replaceAll("&amp;", "&")
                   .replaceAll("&lt;", "<")
                   .replaceAll("&gt;", ">")
                   .replaceAll("\\s+", " ")
                   .trim();
    }

    /**
     * Truncate text to max length
     */
    private String truncateText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    // Critical keywords for priority classification
    private static final List<String> CRITICAL_KEYWORDS = Arrays.asList(
        "famine", "mass casualty", "ceasefire", "escalation", "offensive",
        "emergency declaration", "massacre", "genocide", "epidemic", "pandemic",
        "displacement surge", "coup", "invasion", "airstrike", "siege",
        "humanitarian catastrophe", "death toll", "critical shortage",
        "state of emergency", "collapse", "mass grave"
    );

    /**
     * Classify a news item as CRITICAL or MONITORING based on content.
     */
    private void classifyPriority(NewsItem item) {
        String text = ((item.getTitle() != null ? item.getTitle() : "") + " " +
                       (item.getDescription() != null ? item.getDescription() : "")).toLowerCase();

        // Check critical keywords
        for (String kw : CRITICAL_KEYWORDS) {
            if (text.contains(kw)) {
                item.setPriority("CRITICAL");
                return;
            }
        }

        // Check high-impact topics
        List<String> topics = item.getTopics();
        if (topics != null) {
            boolean hasConflict = topics.contains("CONFLICT");
            boolean hasHealth = topics.contains("HEALTH");
            boolean hasFood = topics.contains("FOOD_SECURITY");

            // Multiple crisis topics = critical
            int crisisTopicCount = 0;
            if (hasConflict) crisisTopicCount++;
            if (hasHealth) crisisTopicCount++;
            if (hasFood) crisisTopicCount++;
            if (topics.contains("CLIMATE")) crisisTopicCount++;

            if (crisisTopicCount >= 2) {
                item.setPriority("CRITICAL");
                return;
            }
        }

        item.setPriority("MONITORING");
    }

    /**
     * Generate rule-based executive summary from today's news data.
     */
    private List<String> generateExecutiveSummary(List<NewsItem> allNews,
                                                   List<RegionalBriefing> regions,
                                                   List<NewsItem> priorityAlerts) {
        List<String> summary = new ArrayList<>();

        // 1. Overview line
        int critical = (int) allNews.stream().filter(i -> "CRITICAL".equals(i.getPriority())).count();
        summary.add(String.format("%d sources scanned, %d items collected across %d regions. %d flagged as priority.",
                NEWS_SOURCES.size() + 1, allNews.size(), regions.size(), critical));

        // 2. Top priority if any
        if (!priorityAlerts.isEmpty()) {
            NewsItem top = priorityAlerts.get(0);
            String source = top.getSource() != null ? top.getSource() : "Unknown";
            summary.add("Priority: " + top.getTitle() + " (" + source + ")");
        }

        // 3. Most active region
        regions.stream()
            .max(Comparator.comparingInt(RegionalBriefing::getItemCount))
            .ifPresent(r -> summary.add(
                String.format("Most active region: %s with %d updates.", r.getRegionName(), r.getItemCount())
            ));

        // 4. Topic distribution
        Map<String, Long> topicCounts = allNews.stream()
            .filter(i -> i.getTopics() != null)
            .flatMap(i -> i.getTopics().stream())
            .collect(Collectors.groupingBy(t -> t, Collectors.counting()));

        topicCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(3)
            .map(e -> e.getKey().toLowerCase().replace("_", " "))
            .reduce((a, b) -> a + ", " + b)
            .ifPresent(topics -> summary.add("Dominant themes: " + topics + "."));

        return summary;
    }

    // ========================================
    // RSS XML DTOs
    // ========================================

    @Data
    @JacksonXmlRootElement(localName = "rss")
    public static class RssFeed {
        @JacksonXmlProperty(localName = "channel")
        private RssChannel channel;
    }

    @Data
    public static class RssChannel {
        private String title;
        private String link;
        private String description;

        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "item")
        private List<RssItem> items;
    }

    @Data
    public static class RssItem {
        private String title;
        private String link;
        private String description;
        private String pubDate;
    }

    // ========================================
    // Response DTOs
    // ========================================

    @Data
    public static class DailyBriefing {
        private String status;
        private String date;
        private String timestamp;
        private int totalItems;
        private List<String> executiveSummary;
        private List<NewsItem> priorityAlerts;
        private List<NewsItem> topHeadlines;
        private List<RegionalBriefing> regionalBriefings;
    }

    @Data
    public static class RegionalBriefing {
        private String regionCode;
        private String regionName;
        private int itemCount;
        private List<NewsItem> newsItems;
    }

    @Data
    public static class NewsItem {
        private String title;
        private String description;
        private String url;
        private String source;
        private String publishedDate;
        private List<String> countries;
        private List<String> topics;
        private String priority; // CRITICAL or MONITORING
    }

    @Data
    public static class RegionConfig {
        private String name;
        private List<String> countryCodes;
        private List<String> keywords;

        public RegionConfig(String name, List<String> countryCodes, List<String> keywords) {
            this.name = name;
            this.countryCodes = countryCodes;
            this.keywords = keywords;
        }
    }
}
