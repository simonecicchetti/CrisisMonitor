package com.crisismonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class RssService {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    // ReliefWeb global feed (filtered by countryNames)
    private static final String RELIEFWEB_GLOBAL = "https://reliefweb.int/updates/rss.xml";

    // Regional feeds loaded from JSON
    private Map<String, RegionFeeds> regionalFeeds = new HashMap<>();

    @Data
    private static class RegionFeeds {
        private String generalUrl;        // BBC regional (works directly)
        private List<String> countryNames; // For filtering ReliefWeb global
    }

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("data/region-mapping.json");
            JsonNode root = objectMapper.readTree(resource.getInputStream());
            JsonNode regions = root.get("regions");

            regions.fieldNames().forEachRemaining(regionCode -> {
                JsonNode region = regions.get(regionCode);
                RegionFeeds feeds = new RegionFeeds();

                // Get BBC regional feed
                JsonNode feedsNode = region.get("feeds");
                if (feedsNode != null && feedsNode.has("general")) {
                    feeds.setGeneralUrl(feedsNode.get("general").asText());
                }

                // Get country names for ReliefWeb filtering
                List<String> names = new ArrayList<>();
                JsonNode namesNode = region.get("countryNames");
                if (namesNode != null && namesNode.isArray()) {
                    namesNode.forEach(n -> names.add(n.asText().toLowerCase(Locale.ROOT)));
                }
                feeds.setCountryNames(names);

                regionalFeeds.put(regionCode, feeds);
            });

            log.info("Loaded regional RSS config for {} regions", regionalFeeds.size());
        } catch (IOException e) {
            log.error("Failed to load regional feeds config: {}", e.getMessage());
        }
    }

    /**
     * Get headlines for a specific region
     * Logic: 2 HUM (ReliefWeb global filtered by countryNames) + 1 GEN (BBC regional)
     */
    @Cacheable(value = "rssHeadlines", key = "#regionCode + '-' + #limit", unless = "#result.isEmpty()")
    public List<RssItem> getRegionHeadlines(String regionCode, int limit) {
        log.info("Fetching RSS headlines for region: {}", regionCode);
        List<RssItem> results = new ArrayList<>();

        RegionFeeds feeds = regionalFeeds.get(regionCode);
        if (feeds == null) {
            log.warn("No feeds configured for region: {}", regionCode);
            return results;
        }

        // 1. Fetch ReliefWeb global and filter by countryNames (HUM) - up to 2 items
        try {
            List<RssItem> allHum = fetchAndParseFeed(RELIEFWEB_GLOBAL, "ReliefWeb", true);
            int humCount = 0;
            for (RssItem item : allHum) {
                if (humCount >= 2) break;
                if (matchesCountryNames(item.getTitle(), feeds.getCountryNames())) {
                    item.setRegion(regionCode);
                    results.add(item);
                    humCount++;
                }
            }
            log.debug("Got {} HUM items for {} (from {} total)", humCount, regionCode, allHum.size());
        } catch (Exception e) {
            log.debug("Failed to fetch ReliefWeb: {}", e.getMessage());
        }

        // 2. Fetch BBC regional (GEN) - 1 item (already region-specific, no filtering)
        if (feeds.getGeneralUrl() != null && results.size() < limit) {
            try {
                List<RssItem> genItems = fetchAndParseFeed(feeds.getGeneralUrl(), "BBC", false);
                if (!genItems.isEmpty()) {
                    RssItem item = genItems.get(0);
                    item.setRegion(regionCode);
                    results.add(item);
                }
                log.debug("Got 1 GEN item for {}", regionCode);
            } catch (Exception e) {
                log.debug("Failed to fetch BBC for {}: {}", regionCode, e.getMessage());
            }
        }

        log.info("Found {} headlines for region {}", results.size(), regionCode);
        return results;
    }

    /**
     * Get all recent headlines (for global context) - one from each region
     */
    @Cacheable(value = "rssGlobal", unless = "#result.isEmpty()")
    public List<RssItem> getGlobalHeadlines(int limit) {
        List<RssItem> results = new ArrayList<>();
        List<String> regionOrder = Arrays.asList("AFRICA", "MENA", "ASIA", "LAC", "EUROPE");

        for (String regionCode : regionOrder) {
            if (results.size() >= limit) break;

            List<RssItem> regionItems = getRegionHeadlines(regionCode, 3);
            if (!regionItems.isEmpty()) {
                results.add(regionItems.get(0));
            }
        }

        return results;
    }

    /**
     * Match title against country names using word boundaries
     * Avoids false positives like "oman" in "accommodation"
     */
    private boolean matchesCountryNames(String title, List<String> countryNames) {
        if (title == null || countryNames == null || countryNames.isEmpty()) {
            return false;
        }
        String lowerTitle = title.toLowerCase(Locale.ROOT);

        for (String country : countryNames) {
            // Use word boundary regex: country must be a complete word
            String regex = "\\b" + Pattern.quote(country) + "\\b";
            if (Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(lowerTitle).find()) {
                return true;
            }
        }
        return false;
    }

    private List<RssItem> fetchAndParseFeed(String url, String sourceName, boolean isHumanitarian) {
        List<RssItem> items = new ArrayList<>();

        try {
            WebClient client = webClientBuilder.build();
            String xml = client.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .block();

            if (xml == null || xml.isEmpty()) {
                return items;
            }

            // Parse RSS items using regex
            Pattern itemPattern = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
            Pattern titlePattern = Pattern.compile("<title><!\\[CDATA\\[(.*?)\\]\\]></title>|<title>(.*?)</title>", Pattern.DOTALL);
            Pattern linkPattern = Pattern.compile("<link>(.*?)</link>");
            Pattern datePattern = Pattern.compile("<pubDate>(.*?)</pubDate>");

            Matcher itemMatcher = itemPattern.matcher(xml);
            while (itemMatcher.find() && items.size() < 20) {
                String itemXml = itemMatcher.group(1);

                RssItem item = new RssItem();
                item.setSource(sourceName);
                item.setHumanitarian(isHumanitarian);

                // Extract title
                Matcher titleMatcher = titlePattern.matcher(itemXml);
                if (titleMatcher.find()) {
                    String title = titleMatcher.group(1) != null ? titleMatcher.group(1) : titleMatcher.group(2);
                    if (title != null) {
                        item.setTitle(cleanHtml(title.trim()));
                    }
                }

                // Extract link
                Matcher linkMatcher = linkPattern.matcher(itemXml);
                if (linkMatcher.find()) {
                    item.setLink(linkMatcher.group(1).trim());
                }

                // Extract date
                Matcher dateMatcher = datePattern.matcher(itemXml);
                if (dateMatcher.find()) {
                    item.setPubDate(formatDate(dateMatcher.group(1).trim()));
                }

                if (item.getTitle() != null && !item.getTitle().isEmpty()) {
                    items.add(item);
                }
            }

        } catch (Exception e) {
            log.debug("Error parsing RSS from {}: {}", url, e.getMessage());
        }

        return items;
    }

    private String cleanHtml(String text) {
        return text.replaceAll("<[^>]+>", "")
                   .replaceAll("&amp;", "&")
                   .replaceAll("&lt;", "<")
                   .replaceAll("&gt;", ">")
                   .replaceAll("&quot;", "\"")
                   .replaceAll("&#39;", "'");
    }

    private String formatDate(String dateStr) {
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(dateStr, DateTimeFormatter.RFC_1123_DATE_TIME);
            LocalDateTime local = zdt.toLocalDateTime();
            LocalDateTime now = LocalDateTime.now();

            long hours = java.time.Duration.between(local, now).toHours();
            if (hours < 1) return "Just now";
            if (hours < 24) return hours + "h ago";
            long days = hours / 24;
            if (days < 7) return days + "d ago";
            return local.format(DateTimeFormatter.ofPattern("MMM d"));
        } catch (Exception e) {
            return dateStr.length() > 16 ? dateStr.substring(0, 16) : dateStr;
        }
    }

    @Data
    public static class RssItem {
        private String title;
        private String link;
        private String source;
        private String pubDate;
        private String region;
        private boolean humanitarian;
    }
}
