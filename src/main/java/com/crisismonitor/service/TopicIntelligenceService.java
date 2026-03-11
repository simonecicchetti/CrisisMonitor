package com.crisismonitor.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Topic-based Intelligence Service
 * Aggregates news and reports by humanitarian themes across regions
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopicIntelligenceService {

    private final GDELTService gdeltService;
    private final ReliefWebService reliefWebService;

    // ========================================
    // TOPIC TAXONOMY
    // ========================================

    public enum Topic {
        MIGRATION("Migration & Displacement",
            "migration OR displacement OR refugees OR asylum OR border OR trafficking OR caravan OR deportation OR migrant"),
        FOOD_CRISIS("Food Crisis",
            "famine OR hunger OR food insecurity OR malnutrition OR starvation OR food aid OR WFP"),
        CONFLICT("Conflict & Violence",
            "conflict OR violence OR military OR attack OR bombing OR casualties OR fighting OR clashes OR armed OR war"),
        CLIMATE_DISASTER("Climate & Disasters",
            "flood OR drought OR cyclone OR hurricane OR earthquake OR landslide OR tsunami OR wildfire OR climate"),
        ECONOMIC_CRISIS("Economic Crisis",
            "inflation OR currency OR economic collapse OR shortage OR sanctions OR hyperinflation OR debt"),
        HEALTH_EMERGENCY("Health Emergency",
            "outbreak OR epidemic OR cholera OR measles OR disease OR vaccination OR pandemic OR malaria"),
        HUMANITARIAN_ACCESS("Humanitarian Access",
            "humanitarian access OR aid blocked OR siege OR blockade OR humanitarian corridor"),
        PROTECTION("Protection & Human Rights",
            "protection OR human rights OR abuse OR gender violence OR child soldiers OR trafficking");

        private final String displayName;
        private final String keywords;

        Topic(String displayName, String keywords) {
            this.displayName = displayName;
            this.keywords = keywords;
        }

        public String getDisplayName() { return displayName; }
        public String getKeywords() { return keywords; }
    }

    // ========================================
    // REGION DEFINITIONS
    // ========================================

    public static final Map<String, List<String>> REGIONS = new LinkedHashMap<>();
    static {
        REGIONS.put("central-america", Arrays.asList("GTM", "HND", "SLV", "NIC", "CRI", "PAN", "BLZ"));
        REGIONS.put("south-america", Arrays.asList("VEN", "COL", "ECU", "PER", "BOL", "BRA", "ARG", "CHL", "PRY", "URY"));
        REGIONS.put("caribbean", Arrays.asList("HTI", "DOM", "CUB", "JAM", "TTO"));
        REGIONS.put("horn-of-africa", Arrays.asList("SOM", "ETH", "ERI", "DJI", "SSD", "SDN", "KEN", "UGA"));
        REGIONS.put("sahel", Arrays.asList("MLI", "NER", "BFA", "TCD", "MRT", "SEN", "GMB"));
        REGIONS.put("west-africa", Arrays.asList("NGA", "GHA", "CIV", "LBR", "SLE", "GIN", "TGO", "BEN", "CMR"));
        REGIONS.put("middle-east", Arrays.asList("SYR", "IRQ", "YEM", "LBN", "JOR", "PSE", "ISR"));
        REGIONS.put("central-asia", Arrays.asList("AFG", "PAK", "TJK", "UZB", "TKM", "KGZ"));
        REGIONS.put("southeast-asia", Arrays.asList("MMR", "THA", "VNM", "LAO", "KHM", "IDN", "PHL", "MYS"));
        REGIONS.put("eastern-europe", Arrays.asList("UKR", "MDA", "BLR", "RUS"));
    }

    public static final Map<String, String> REGION_NAMES = new LinkedHashMap<>();
    static {
        REGION_NAMES.put("central-america", "Central America");
        REGION_NAMES.put("south-america", "South America");
        REGION_NAMES.put("caribbean", "Caribbean");
        REGION_NAMES.put("horn-of-africa", "Horn of Africa");
        REGION_NAMES.put("sahel", "Sahel");
        REGION_NAMES.put("west-africa", "West Africa");
        REGION_NAMES.put("middle-east", "Middle East");
        REGION_NAMES.put("central-asia", "Central Asia");
        REGION_NAMES.put("southeast-asia", "Southeast Asia");
        REGION_NAMES.put("eastern-europe", "Eastern Europe");
    }

    // Country name mapping for GDELT queries
    private static final Map<String, String> ISO3_TO_NAME = new HashMap<>();
    static {
        ISO3_TO_NAME.put("GTM", "guatemala");
        ISO3_TO_NAME.put("HND", "honduras");
        ISO3_TO_NAME.put("SLV", "el salvador");
        ISO3_TO_NAME.put("NIC", "nicaragua");
        ISO3_TO_NAME.put("CRI", "costa rica");
        ISO3_TO_NAME.put("PAN", "panama");
        ISO3_TO_NAME.put("BLZ", "belize");
        ISO3_TO_NAME.put("VEN", "venezuela");
        ISO3_TO_NAME.put("COL", "colombia");
        ISO3_TO_NAME.put("ECU", "ecuador");
        ISO3_TO_NAME.put("PER", "peru");
        ISO3_TO_NAME.put("BOL", "bolivia");
        ISO3_TO_NAME.put("BRA", "brazil");
        ISO3_TO_NAME.put("ARG", "argentina");
        ISO3_TO_NAME.put("CHL", "chile");
        ISO3_TO_NAME.put("PRY", "paraguay");
        ISO3_TO_NAME.put("URY", "uruguay");
        ISO3_TO_NAME.put("HTI", "haiti");
        ISO3_TO_NAME.put("DOM", "dominican republic");
        ISO3_TO_NAME.put("CUB", "cuba");
        ISO3_TO_NAME.put("JAM", "jamaica");
        ISO3_TO_NAME.put("TTO", "trinidad");
        ISO3_TO_NAME.put("SOM", "somalia");
        ISO3_TO_NAME.put("ETH", "ethiopia");
        ISO3_TO_NAME.put("ERI", "eritrea");
        ISO3_TO_NAME.put("DJI", "djibouti");
        ISO3_TO_NAME.put("SSD", "south sudan");
        ISO3_TO_NAME.put("SDN", "sudan");
        ISO3_TO_NAME.put("KEN", "kenya");
        ISO3_TO_NAME.put("UGA", "uganda");
        ISO3_TO_NAME.put("MLI", "mali");
        ISO3_TO_NAME.put("NER", "niger");
        ISO3_TO_NAME.put("BFA", "burkina faso");
        ISO3_TO_NAME.put("TCD", "chad");
        ISO3_TO_NAME.put("MRT", "mauritania");
        ISO3_TO_NAME.put("SEN", "senegal");
        ISO3_TO_NAME.put("GMB", "gambia");
        ISO3_TO_NAME.put("NGA", "nigeria");
        ISO3_TO_NAME.put("GHA", "ghana");
        ISO3_TO_NAME.put("CIV", "ivory coast");
        ISO3_TO_NAME.put("LBR", "liberia");
        ISO3_TO_NAME.put("SLE", "sierra leone");
        ISO3_TO_NAME.put("GIN", "guinea");
        ISO3_TO_NAME.put("TGO", "togo");
        ISO3_TO_NAME.put("BEN", "benin");
        ISO3_TO_NAME.put("CMR", "cameroon");
        ISO3_TO_NAME.put("SYR", "syria");
        ISO3_TO_NAME.put("IRQ", "iraq");
        ISO3_TO_NAME.put("YEM", "yemen");
        ISO3_TO_NAME.put("LBN", "lebanon");
        ISO3_TO_NAME.put("JOR", "jordan");
        ISO3_TO_NAME.put("PSE", "palestinian");
        ISO3_TO_NAME.put("ISR", "israel");
        ISO3_TO_NAME.put("AFG", "afghanistan");
        ISO3_TO_NAME.put("PAK", "pakistan");
        ISO3_TO_NAME.put("TJK", "tajikistan");
        ISO3_TO_NAME.put("UZB", "uzbekistan");
        ISO3_TO_NAME.put("TKM", "turkmenistan");
        ISO3_TO_NAME.put("KGZ", "kyrgyzstan");
        ISO3_TO_NAME.put("MMR", "myanmar");
        ISO3_TO_NAME.put("THA", "thailand");
        ISO3_TO_NAME.put("VNM", "vietnam");
        ISO3_TO_NAME.put("LAO", "laos");
        ISO3_TO_NAME.put("KHM", "cambodia");
        ISO3_TO_NAME.put("IDN", "indonesia");
        ISO3_TO_NAME.put("PHL", "philippines");
        ISO3_TO_NAME.put("MYS", "malaysia");
        ISO3_TO_NAME.put("UKR", "ukraine");
        ISO3_TO_NAME.put("MDA", "moldova");
        ISO3_TO_NAME.put("BLR", "belarus");
        ISO3_TO_NAME.put("RUS", "russia");
        ISO3_TO_NAME.put("CAF", "central african republic");
        ISO3_TO_NAME.put("COD", "congo");
        ISO3_TO_NAME.put("MOZ", "mozambique");
        ISO3_TO_NAME.put("ZWE", "zimbabwe");
        ISO3_TO_NAME.put("BGD", "bangladesh");
        ISO3_TO_NAME.put("IND", "india");
        ISO3_TO_NAME.put("LBY", "libya");
    }

    private static final Map<String, String> ISO3_TO_DISPLAY = new HashMap<>();
    static {
        ISO3_TO_DISPLAY.put("GTM", "Guatemala");
        ISO3_TO_DISPLAY.put("HND", "Honduras");
        ISO3_TO_DISPLAY.put("SLV", "El Salvador");
        ISO3_TO_DISPLAY.put("NIC", "Nicaragua");
        ISO3_TO_DISPLAY.put("VEN", "Venezuela");
        ISO3_TO_DISPLAY.put("COL", "Colombia");
        ISO3_TO_DISPLAY.put("ECU", "Ecuador");
        ISO3_TO_DISPLAY.put("PER", "Peru");
        ISO3_TO_DISPLAY.put("HTI", "Haiti");
        ISO3_TO_DISPLAY.put("SOM", "Somalia");
        ISO3_TO_DISPLAY.put("ETH", "Ethiopia");
        ISO3_TO_DISPLAY.put("SSD", "South Sudan");
        ISO3_TO_DISPLAY.put("SDN", "Sudan");
        ISO3_TO_DISPLAY.put("YEM", "Yemen");
        ISO3_TO_DISPLAY.put("SYR", "Syria");
        ISO3_TO_DISPLAY.put("AFG", "Afghanistan");
        ISO3_TO_DISPLAY.put("MMR", "Myanmar");
        ISO3_TO_DISPLAY.put("UKR", "Ukraine");
        ISO3_TO_DISPLAY.put("NGA", "Nigeria");
        ISO3_TO_DISPLAY.put("MLI", "Mali");
        ISO3_TO_DISPLAY.put("BFA", "Burkina Faso");
        ISO3_TO_DISPLAY.put("NER", "Niger");
        ISO3_TO_DISPLAY.put("TCD", "Chad");
        ISO3_TO_DISPLAY.put("LBN", "Lebanon");
        ISO3_TO_DISPLAY.put("IRQ", "Iraq");
        ISO3_TO_DISPLAY.put("PAK", "Pakistan");
        ISO3_TO_DISPLAY.put("BGD", "Bangladesh");
        ISO3_TO_DISPLAY.put("CAF", "CAR");
        ISO3_TO_DISPLAY.put("COD", "DR Congo");
        ISO3_TO_DISPLAY.put("MOZ", "Mozambique");
        ISO3_TO_DISPLAY.put("KEN", "Kenya");
        ISO3_TO_DISPLAY.put("UGA", "Uganda");
    }

    // ========================================
    // MAIN SEARCH METHOD
    // ========================================

    /**
     * Search intelligence by topic and optional region/country
     */
    @Cacheable(value = "topicIntelligence", key = "#topic + '_' + #region + '_' + #countryIso3")
    public TopicIntelligenceResult searchByTopic(String topic, String region, String countryIso3) {
        log.info("Searching intelligence: topic={}, region={}, country={}", topic, region, countryIso3);

        Topic topicEnum;
        try {
            topicEnum = Topic.valueOf(topic.toUpperCase().replace("-", "_").replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown topic: {}, defaulting to CONFLICT", topic);
            topicEnum = Topic.CONFLICT;
        }

        // Determine countries to search
        List<String> countries = resolveCountries(region, countryIso3);

        TopicIntelligenceResult result = new TopicIntelligenceResult();
        result.setTopic(topicEnum.name());
        result.setTopicDisplayName(topicEnum.getDisplayName());
        result.setRegion(region);
        result.setRegionDisplayName(REGION_NAMES.getOrDefault(region, region));
        result.setTimestamp(LocalDateTime.now());

        // Fetch GDELT headlines by topic + countries
        List<TopicHeadline> headlines = fetchGdeltByTopic(topicEnum, countries);
        result.setHeadlines(headlines);
        result.setTotalArticles(headlines.stream().mapToInt(h -> 1).sum());

        // Fetch ReliefWeb reports
        List<TopicReport> reports = fetchReliefWebByTopic(topicEnum, countries);
        result.setOfficialReports(reports);

        // Calculate per-country breakdown
        Map<String, CountryTopicStats> countryStats = calculateCountryStats(topicEnum, countries);
        result.setCountryBreakdown(countryStats);

        // Calculate trend (simplified - compare keywords in headlines)
        result.setTrendDirection(calculateTrend(headlines));

        log.info("Topic search complete: {} headlines, {} reports", headlines.size(), reports.size());
        return result;
    }

    /**
     * Get all available topics
     */
    public List<Map<String, String>> getAvailableTopics() {
        return Arrays.stream(Topic.values())
            .map(t -> Map.of(
                "id", t.name().toLowerCase().replace("_", "-"),
                "name", t.getDisplayName()
            ))
            .collect(Collectors.toList());
    }

    /**
     * Get all available regions
     */
    public List<Map<String, String>> getAvailableRegions() {
        return REGION_NAMES.entrySet().stream()
            .map(e -> Map.of("id", e.getKey(), "name", e.getValue()))
            .collect(Collectors.toList());
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    private List<String> resolveCountries(String region, String countryIso3) {
        if (countryIso3 != null && !countryIso3.isEmpty()) {
            return Collections.singletonList(countryIso3.toUpperCase());
        }
        if (region != null && REGIONS.containsKey(region.toLowerCase())) {
            return REGIONS.get(region.toLowerCase());
        }
        // Default: all monitored countries
        return new ArrayList<>(ISO3_TO_NAME.keySet());
    }

    private List<TopicHeadline> fetchGdeltByTopic(Topic topic, List<String> countries) {
        List<TopicHeadline> allHeadlines = new ArrayList<>();

        // Build country query part
        String countryQuery = countries.stream()
            .map(iso -> ISO3_TO_NAME.getOrDefault(iso, iso.toLowerCase()))
            .map(name -> "\"" + name + "\"")
            .collect(Collectors.joining(" OR "));

        // For each country, get headlines with topic keywords (increased limit)
        for (String iso3 : countries.subList(0, Math.min(countries.size(), 15))) {
            try {
                String countryName = ISO3_TO_NAME.getOrDefault(iso3, iso3.toLowerCase());
                var headlines = gdeltService.getTopHeadlinesWithUrls(iso3, 3);

                if (headlines != null) {
                    for (var h : headlines) {
                        // Filter by topic keywords (simple check)
                        String titleLower = h.getTitle().toLowerCase();
                        boolean matchesTopic = Arrays.stream(topic.getKeywords().split(" OR "))
                            .map(String::trim)
                            .anyMatch(kw -> titleLower.contains(kw.toLowerCase()));

                        if (matchesTopic || topic == Topic.CONFLICT) { // CONFLICT is default
                            TopicHeadline th = new TopicHeadline();
                            th.setTitle(h.getTitle());
                            th.setUrl(h.getUrl());
                            th.setSource(h.getSource());
                            th.setCountryIso3(iso3);
                            th.setCountryName(ISO3_TO_DISPLAY.getOrDefault(iso3, iso3));
                            th.setTopic(topic.name());
                            allHeadlines.add(th);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Error fetching headlines for {}: {}", iso3, e.getMessage());
            }
        }

        // Sort by relevance (topic match first) and limit
        return allHeadlines.stream()
            .limit(12)
            .collect(Collectors.toList());
    }

    private List<TopicReport> fetchReliefWebByTopic(Topic topic, List<String> countries) {
        List<TopicReport> reports = new ArrayList<>();

        // Map topic to ReliefWeb theme
        String reliefWebTheme = mapTopicToReliefWebTheme(topic);

        // Fetch reports for top countries (increased limit)
        for (String iso3 : countries.subList(0, Math.min(countries.size(), 10))) {
            try {
                var rwReports = reliefWebService.getLatestReports(iso3, 2);
                if (rwReports != null) {
                    for (var r : rwReports) {
                        TopicReport tr = new TopicReport();
                        tr.setTitle(r.getTitle());
                        tr.setUrl(r.getUrl());
                        tr.setSource(r.getSource());
                        tr.setDate(r.getDate());
                        tr.setCountryIso3(iso3);
                        tr.setCountryName(ISO3_TO_DISPLAY.getOrDefault(iso3, iso3));
                        reports.add(tr);
                    }
                }
            } catch (Exception e) {
                log.debug("Error fetching ReliefWeb for {}: {}", iso3, e.getMessage());
            }
        }

        return reports.stream().limit(8).collect(Collectors.toList());
    }

    private String mapTopicToReliefWebTheme(Topic topic) {
        return switch (topic) {
            case MIGRATION -> "Population Movement";
            case FOOD_CRISIS -> "Food and Nutrition";
            case CONFLICT -> "Protection and Human Rights";
            case CLIMATE_DISASTER -> "Climate Change and Environment";
            case HEALTH_EMERGENCY -> "Health";
            case ECONOMIC_CRISIS -> "Food and Nutrition"; // Economic often affects food
            case HUMANITARIAN_ACCESS -> "Humanitarian Financing";
            case PROTECTION -> "Protection and Human Rights";
        };
    }

    private Map<String, CountryTopicStats> calculateCountryStats(Topic topic, List<String> countries) {
        Map<String, CountryTopicStats> stats = new LinkedHashMap<>();

        for (String iso3 : countries.subList(0, Math.min(countries.size(), 12))) {
            try {
                var spike = gdeltService.getConflictSpikeIndex(iso3);
                if (spike != null) {
                    CountryTopicStats cs = new CountryTopicStats();
                    cs.setIso3(iso3);
                    cs.setCountryName(ISO3_TO_DISPLAY.getOrDefault(iso3, iso3));
                    cs.setArticleCount(spike.getArticlesLast7Days());
                    cs.setZScore(spike.getZScore());
                    cs.setSpikeLevel(spike.getSpikeLevel());
                    stats.put(iso3, cs);
                }
            } catch (Exception e) {
                log.debug("Error getting stats for {}: {}", iso3, e.getMessage());
            }
        }

        // Sort by article count descending
        return stats.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().getArticleCount(), a.getValue().getArticleCount()))
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (e1, e2) -> e1,
                LinkedHashMap::new
            ));
    }

    private String calculateTrend(List<TopicHeadline> headlines) {
        // Simplified: if we have headlines, trend is active
        if (headlines.size() > 8) return "HIGH";
        if (headlines.size() > 4) return "MODERATE";
        if (headlines.size() > 0) return "LOW";
        return "NONE";
    }

    // ========================================
    // RESULT DTOs
    // ========================================

    @Data
    public static class TopicIntelligenceResult {
        private String topic;
        private String topicDisplayName;
        private String region;
        private String regionDisplayName;
        private LocalDateTime timestamp;
        private int totalArticles;
        private String trendDirection;
        private List<TopicHeadline> headlines;
        private List<TopicReport> officialReports;
        private Map<String, CountryTopicStats> countryBreakdown;
    }

    @Data
    public static class TopicHeadline {
        private String title;
        private String url;
        private String source;
        private String countryIso3;
        private String countryName;
        private String topic;
    }

    @Data
    public static class TopicReport {
        private String title;
        private String url;
        private String source;
        private String date;
        private String countryIso3;
        private String countryName;
    }

    @Data
    public static class CountryTopicStats {
        private String iso3;
        private String countryName;
        private int articleCount;
        private double zScore;
        private String spikeLevel;
    }
}
