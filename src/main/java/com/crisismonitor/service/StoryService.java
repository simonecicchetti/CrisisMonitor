package com.crisismonitor.service;

import com.crisismonitor.model.Headline;
import com.crisismonitor.model.MediaSpike;
import com.crisismonitor.model.NewsItem;
import com.crisismonitor.model.Story;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.crisismonitor.config.MonitoredCountries;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Story Service - transforms raw GDELT headlines into deduped Story clusters.
 *
 * This is the core of the "News Feed" lane - making noise into signal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoryService {

    private final GDELTService gdeltService;
    private final CacheWarmupService cacheWarmupService;
    private final ReliefWebService reliefWebService;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    @Value("${claude.api.key:}")
    private String claudeApiKey;

    private final WebClient claudeClient = WebClient.builder()
            .baseUrl("https://api.anthropic.com/v1")
            .build();

    // RSS News Sources for humanitarian and migration coverage
    private static final Map<String, String> RSS_FEEDS = Map.ofEntries(
        // Major news with LAC coverage
        Map.entry("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"),
        // UN/Humanitarian sources
        Map.entry("UN News Migration", "https://news.un.org/feed/subscribe/en/news/topic/migrants-and-refugees/feed/rss.xml"),
        Map.entry("UN News LAC", "https://news.un.org/feed/subscribe/en/news/region/americas/feed/rss.xml"),
        Map.entry("ReliefWeb RSS", "https://reliefweb.int/updates/rss.xml"),
        Map.entry("ICRC", "https://www.icrc.org/en/rss/news"),
        // Migration-specific sources
        Map.entry("MMC", "https://mixedmigration.org/feed/"),
        Map.entry("IOM News", "https://www.iom.int/news/rss.xml"),
        // LAC specialized
        Map.entry("InSight Crime", "https://insightcrime.org/feed/"),
        Map.entry("Americas Quarterly", "https://www.americasquarterly.org/feed/"),
        Map.entry("The Guardian Americas", "https://www.theguardian.com/world/americas/rss")
    );

    // LAC keywords for region detection (expanded with migration routes)
    private static final List<String> LAC_KEYWORDS = List.of(
        // Countries
        "venezuela", "venezuelan", "colombia", "colombian", "haiti", "haitian",
        "guatemala", "guatemalan", "honduras", "honduran", "el salvador", "salvadoran",
        "nicaragua", "nicaraguan", "mexico", "mexican", "peru", "peruvian",
        "ecuador", "ecuadorian", "bolivia", "brazil", "brazilian", "chile", "chilean",
        "argentina", "cuba", "cuban", "dominican", "panama", "panamanian", "costa rica",
        // Regions
        "central america", "central american", "latin america", "caribbean", "south america",
        // Migration routes & key cities
        "darien", "darien gap", "tapachula", "ciudad juarez", "tijuana", "reynosa",
        "matamoros", "nuevo laredo", "nogales", "el paso", "rio grande", "us-mexico border",
        "southern border", "northern triangle",
        // Capitals & major cities
        "caracas", "bogota", "port-au-prince", "tegucigalpa", "san salvador",
        "managua", "guatemala city", "mexico city", "lima", "quito", "la paz",
        // Political figures
        "maduro", "petro", "bukele", "amlo", "sheinbaum",
        // Migration-specific
        "caravan", "migrant", "migration", "deportation", "tps", "title 42",
        "remain in mexico", "mpp", "cbp one", "asylum seeker"
    );

    // Topic detection keywords (expanded for better coverage)
    private static final Map<String, List<String>> TOPIC_KEYWORDS = Map.of(
        "conflict", List.of("war", "attack", "violence", "military", "armed", "killed", "killing", "fighting", "troops", "bomb", "strike", "casualties", "clashes", "offensive", "airstrike", "militia", "rebel", "insurgent", "ceasefire", "battle", "shelling", "rsf", "forces", "army", "paramilitary", "terror", "gang", "gangs", "assassination", "coup", "protests", "unrest", "hamas", "hezbollah", "taliban", "al-shabaab", "boko haram", "m23", "junta", "siege"),
        "migration", List.of(
            // Core terms
            "migrant", "migrants", "refugee", "refugees", "asylum", "border", "displaced", "displacement",
            "flee", "fleeing", "exodus", "deportation", "deportee", "immigration", "emigration",
            // Organizations
            "unhcr", "iom", "dtm", "r4v",
            // Status types
            "idp", "internally displaced", "forced displacement", "population movement",
            "tps", "temporary protected status", "asylum seeker", "returnee", "repatriation",
            // Actions
            "shelter", "resettlement", "crossing", "smuggling", "trafficking", "expulsion",
            // LAC-specific routes
            "darien", "darien gap", "tapachula", "ciudad juarez", "tijuana", "border crossing",
            "caravan", "migrant caravan", "northern triangle", "southern border",
            // LAC nationalities (triggers migration context)
            "venezuelan", "haitian", "cuban", "nicaraguan", "honduran", "guatemalan", "salvadoran",
            "central american", "colombian migrants", "ecuadorian migrants", "peruvian migrants",
            // US policies
            "title 42", "remain in mexico", "mpp", "cbp one", "ice detention", "border patrol",
            // Humanitarian corridors
            "humanitarian corridor", "safe passage", "transit country", "mixed migration", "irregular migration"
        ),
        "food", List.of("hunger", "famine", "food", "malnutrition", "starvation", "wfp", "crops", "drought", "harvest", "ipc", "acute", "insecurity", "rations", "aid", "relief", "grain", "wheat", "rice", "livestock", "nutrition", "feeding"),
        "climate", List.of("flood", "cyclone", "hurricane", "drought", "earthquake", "disaster", "storm", "climate", "wildfire", "landslide", "tsunami", "monsoon", "erosion", "el nino", "la nina", "typhoon", "tornado", "heatwave", "precipitation", "rainfall", "mudslide"),
        "health", List.of("outbreak", "epidemic", "cholera", "disease", "hospital", "who", "vaccination", "health", "medical", "polio", "measles", "ebola", "malaria", "dengue", "pandemic", "mpox", "infection", "virus", "mortality", "deaths"),
        "humanitarian", List.of("humanitarian", "crisis", "emergency", "aid", "relief", "donor", "ocha", "icrc", "ngos", "pledges", "funding", "response", "assistance", "access", "blockade", "sanctions", "embargo", "intervention", "peacekeeping", "un", "united nations")
    );

    // Region mapping (comprehensive to support detectCountryFromText results)
    private static final Map<String, String> COUNTRY_TO_REGION = new HashMap<>();
    static {
        // Africa (East, Central, West, Southern)
        for (String c : List.of(
            // East Africa
            "SDN", "SSD", "ETH", "SOM", "KEN", "UGA", "RWA", "BDI", "TZA", "ERI", "DJI",
            // Central Africa
            "COD", "CAF", "CMR", "TCD",
            // West Africa
            "NGA", "MLI", "BFA", "NER", "SEN", "GHA",
            // Southern/Other Africa
            "MOZ", "ZAF", "ZWE", "MWI", "LBY"
        )) {
            COUNTRY_TO_REGION.put(c, "Africa");
        }
        // LAC (Latin America & Caribbean)
        for (String c : List.of("HTI", "VEN", "COL", "GTM", "HND", "SLV", "NIC", "MEX", "PER", "ECU", "BOL", "BRA", "ARG", "CHL", "CUB", "DOM", "PAN", "CRI")) {
            COUNTRY_TO_REGION.put(c, "LAC");
        }
        // MENA
        for (String c : List.of("SYR", "IRQ", "YEM", "LBN", "JOR", "PSE", "ISR", "EGY", "TUN", "DZA", "MAR", "IRN", "SAU", "ARE")) {
            COUNTRY_TO_REGION.put(c, "MENA");
        }
        // Asia
        for (String c : List.of("AFG", "PAK", "BGD", "MMR", "THA", "VNM", "PHL", "IDN", "MYS", "IND", "NPL", "LKA", "CHN", "PRK")) {
            COUNTRY_TO_REGION.put(c, "Asia");
        }
        // Europe (including neighboring countries)
        for (String c : List.of("UKR", "RUS", "POL", "DEU", "FRA", "GBR", "ITA", "ESP", "GRC", "TUR", "SRB", "BIH", "HRV", "ROU", "BGR", "HUN", "BLR", "MDA", "NOR", "SWE", "FIN", "DNK", "NLD", "BEL", "CHE", "AUT", "PRT", "IRL", "CZE", "SVK")) {
            COUNTRY_TO_REGION.put(c, "Europe");
        }
        // North America
        for (String c : List.of("USA", "CAN")) {
            COUNTRY_TO_REGION.put(c, "North America");
        }
    }

    // Country names
    private static final Map<String, String> ISO3_TO_NAME = new HashMap<>();
    static {
        ISO3_TO_NAME.put("SDN", "Sudan"); ISO3_TO_NAME.put("SSD", "South Sudan"); ISO3_TO_NAME.put("ETH", "Ethiopia");
        ISO3_TO_NAME.put("SOM", "Somalia"); ISO3_TO_NAME.put("KEN", "Kenya"); ISO3_TO_NAME.put("UGA", "Uganda");
        ISO3_TO_NAME.put("COD", "DR Congo"); ISO3_TO_NAME.put("CAF", "Central African Republic"); ISO3_TO_NAME.put("NGA", "Nigeria");
        ISO3_TO_NAME.put("HTI", "Haiti"); ISO3_TO_NAME.put("VEN", "Venezuela"); ISO3_TO_NAME.put("COL", "Colombia");
        ISO3_TO_NAME.put("GTM", "Guatemala"); ISO3_TO_NAME.put("HND", "Honduras"); ISO3_TO_NAME.put("SLV", "El Salvador");
        ISO3_TO_NAME.put("SYR", "Syria"); ISO3_TO_NAME.put("IRQ", "Iraq"); ISO3_TO_NAME.put("YEM", "Yemen");
        ISO3_TO_NAME.put("LBN", "Lebanon"); ISO3_TO_NAME.put("PSE", "Palestine"); ISO3_TO_NAME.put("AFG", "Afghanistan");
        ISO3_TO_NAME.put("PAK", "Pakistan"); ISO3_TO_NAME.put("BGD", "Bangladesh"); ISO3_TO_NAME.put("MMR", "Myanmar");
        ISO3_TO_NAME.put("UKR", "Ukraine"); ISO3_TO_NAME.put("RUS", "Russia"); ISO3_TO_NAME.put("MEX", "Mexico");
        ISO3_TO_NAME.put("PER", "Peru"); ISO3_TO_NAME.put("ECU", "Ecuador"); ISO3_TO_NAME.put("BOL", "Bolivia");
        ISO3_TO_NAME.put("NIC", "Nicaragua"); ISO3_TO_NAME.put("LBY", "Libya"); ISO3_TO_NAME.put("MLI", "Mali");
        ISO3_TO_NAME.put("BFA", "Burkina Faso"); ISO3_TO_NAME.put("NER", "Niger"); ISO3_TO_NAME.put("TCD", "Chad");
        ISO3_TO_NAME.put("USA", "United States"); ISO3_TO_NAME.put("CAN", "Canada");
    }

    /**
     * Get today's stories - deduped and clustered
     * Note: No separate cache - uses same cache as getStories() to avoid inconsistency
     */
    public List<Story> getTodayStories() {
        return getStories(null, null, 1);
    }

    /**
     * Get stories with filters
     */
    @Cacheable(value = "storiesV16", key = "(#region?.toLowerCase() ?: '') + '-' + (#topic?.toLowerCase() ?: '') + '-' + #days")
    public List<Story> getStories(String region, String topic, int days) {
        log.info("Building stories: region={}, topic={}, days={}", region, topic, days);

        try {
            List<RawHeadline> rawHeadlines = new ArrayList<>();

            // 1. Fetch from GDELT (primary source)
            List<RawHeadline> gdeltHeadlines = fetchGdeltHeadlines(days);
            log.info("Fetched {} raw headlines from GDELT", gdeltHeadlines.size());
            rawHeadlines.addAll(gdeltHeadlines);

            // 2. Fetch from ReliefWeb (humanitarian reports)
            List<RawHeadline> reliefWebHeadlines = fetchReliefWebHeadlines();
            log.info("Fetched {} raw headlines from ReliefWeb", reliefWebHeadlines.size());
            rawHeadlines.addAll(reliefWebHeadlines);

            // 3. Fetch from RSS feeds (IOM, UNHCR, Al Jazeera, etc. - for migration coverage)
            List<RawHeadline> rssHeadlines = fetchRssHeadlines();
            log.info("Fetched {} raw headlines from RSS feeds", rssHeadlines.size());
            rawHeadlines.addAll(rssHeadlines);

            if (rawHeadlines.isEmpty()) {
                log.warn("No headlines from any source");
                return Collections.emptyList();
            }

            log.info("Total raw headlines from all sources: {}", rawHeadlines.size());

            // Cluster into stories
            List<Story> stories = clusterIntoStories(rawHeadlines);
            log.info("Clustered into {} stories", stories.size());

            // Filter by region if specified
            if (region != null && !region.isBlank()) {
                stories = stories.stream()
                        .filter(s -> region.equalsIgnoreCase(s.getRegion()))
                        .collect(Collectors.toList());
            }

            // Filter by topic if specified
            if (topic != null && !topic.isBlank()) {
                String topicLower = topic.toLowerCase();
                stories = stories.stream()
                        .filter(s -> s.getTopicTags() != null && s.getTopicTags().contains(topicLower))
                        .collect(Collectors.toList());
            }

            // Sort by volume (most coverage first)
            stories.sort((a, b) -> Integer.compare(b.getVolume24h(), a.getVolume24h()));

            // Limit to top 50 (create new ArrayList to avoid serialization issues with SubList)
            if (stories.size() > 50) {
                stories = new ArrayList<>(stories.subList(0, 50));
            }

            log.info("Returning {} stories after filters", stories.size());
            return stories;

        } catch (Exception e) {
            log.error("Error building stories: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Get top stories for overview (max 5)
     */
    public List<Story> getTopStories(int limit) {
        List<Story> all = getTodayStories();
        return all.stream().limit(limit).collect(Collectors.toList());
    }

    /**
     * Get stories by region for overview
     */
    public Map<String, List<Story>> getStoriesByRegion() {
        List<Story> all = getTodayStories();
        return all.stream()
                .filter(s -> s.getRegion() != null)
                .collect(Collectors.groupingBy(Story::getRegion));
    }

    /**
     * Fetch raw headlines from cached GDELT spikes data.
     * Uses getAllConflictSpikes which is pre-cached by warmup service.
     * Returns empty list immediately if cache is not ready (non-blocking).
     */
    private List<RawHeadline> fetchGdeltHeadlines(int days) {
        List<RawHeadline> headlines = new ArrayList<>();

        // Check if GDELT cache is ready - don't block if warmup is in progress
        if (!cacheWarmupService.isCacheReady("conflict")) {
            log.info("GDELT cache not ready yet - warmup in progress");
            return headlines; // Return empty list immediately
        }

        try {
            // Try to get from in-memory fallback first (fastest)
            @SuppressWarnings("unchecked")
            List<MediaSpike> spikes = cacheWarmupService.getFallback("gdeltAllSpikes");

            if (spikes == null || spikes.isEmpty()) {
                // Fallback to cache/API call
                spikes = gdeltService.getAllConflictSpikes();
            }

            if (spikes == null || spikes.isEmpty()) {
                log.info("No GDELT spikes available yet");
                return headlines;
            }

            for (MediaSpike spike : spikes) {
                if (spike.getTopHeadlines() != null) {
                    for (String title : spike.getTopHeadlines()) {
                        if (title != null && !title.isBlank()) {
                            RawHeadline raw = new RawHeadline();
                            raw.title = title;
                            raw.country = spike.getIso3();
                            raw.source = "GDELT";
                            headlines.add(raw);
                        }
                    }
                }
            }

            log.info("Fetched {} headlines from GDELT spikes", headlines.size());

        } catch (Exception e) {
            log.warn("Error fetching headlines: {} - returning empty", e.getMessage());
        }

        return headlines;
    }

    /**
     * Fallback: Fetch headlines from ReliefWeb when GDELT is not ready.
     * Expanded to include more countries across all regions.
     */
    private List<RawHeadline> fetchReliefWebHeadlines() {
        List<RawHeadline> headlines = new ArrayList<>();

        // Priority countries by region for balanced coverage
        List<String> priorityCountries = List.of(
            // Africa (Horn + Sahel)
            "SDN", "SSD", "ETH", "SOM", "COD", "NGA", "MLI", "BFA",
            // MENA
            "SYR", "YEM", "PSE", "LBN", "IRQ",
            // LAC (expanded for migration coverage)
            "HTI", "VEN", "COL", "GTM", "HND", "MEX", "NIC",
            // Asia
            "AFG", "MMR", "BGD", "PAK",
            // Europe
            "UKR"
        );

        for (String iso3 : priorityCountries) {
            try {
                var reports = reliefWebService.getLatestReports(iso3, 2);
                if (reports != null) {
                    for (var report : reports) {
                        if (report.getTitle() != null && !report.getTitle().isBlank()) {
                            RawHeadline raw = new RawHeadline();
                            raw.title = report.getTitle();
                            raw.url = report.getUrl();
                            raw.source = report.getSource();
                            raw.country = iso3;
                            headlines.add(raw);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Error fetching ReliefWeb for {}: {}", iso3, e.getMessage());
            }
        }

        return headlines;
    }

    /**
     * Fetch headlines from RSS news feeds (IOM, UNHCR, Al Jazeera, etc.)
     * Focuses on migration and humanitarian news.
     */
    private List<RawHeadline> fetchRssHeadlines() {
        List<RawHeadline> headlines = new ArrayList<>();
        WebClient webClient = webClientBuilder.build();
        XmlMapper xmlMapper = new XmlMapper();
        xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        for (Map.Entry<String, String> feed : RSS_FEEDS.entrySet()) {
            String sourceName = feed.getKey();
            String feedUrl = feed.getValue();

            try {
                String xml = webClient.get()
                    .uri(feedUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(8))
                    .block();

                if (xml == null || xml.isEmpty()) continue;

                RssFeed rssFeed = xmlMapper.readValue(xml, RssFeed.class);
                if (rssFeed != null && rssFeed.getChannel() != null && rssFeed.getChannel().getItems() != null) {
                    for (RssItem item : rssFeed.getChannel().getItems()) {
                        if (item.getTitle() == null || item.getTitle().isBlank()) continue;

                        String titleLower = item.getTitle().toLowerCase();
                        String descLower = item.getDescription() != null ? item.getDescription().toLowerCase() : "";
                        String combined = titleLower + " " + descLower;

                        // Detect country/region from content
                        String country = detectCountryFromText(combined);

                        RawHeadline raw = new RawHeadline();
                        raw.title = cleanHtml(item.getTitle());
                        raw.url = item.getLink();
                        raw.source = sourceName;
                        raw.country = country;
                        headlines.add(raw);
                    }
                }
                log.debug("Fetched {} items from RSS: {}",
                    rssFeed != null && rssFeed.getChannel() != null && rssFeed.getChannel().getItems() != null
                        ? rssFeed.getChannel().getItems().size() : 0, sourceName);

            } catch (Exception e) {
                log.debug("Error fetching RSS from {}: {}", sourceName, e.getMessage());
            }
        }

        log.info("Fetched {} total headlines from RSS feeds", headlines.size());
        return headlines;
    }

    /**
     * Detect country ISO3 from text content
     * Priority: specific countries first, then regional indicators
     * IMPORTANT: Must detect non-LAC countries to prevent false region assignment
     */
    private String detectCountryFromText(String text) {
        // ========== AFRICA (must be detected to prevent LAC false positives) ==========
        // East Africa
        if (text.contains("tanzania") || text.contains("tanzanian") || text.contains("dar es salaam")) return "TZA";
        if (text.contains("kenya") || text.contains("kenyan") || text.contains("nairobi")) return "KEN";
        if (text.contains("uganda") || text.contains("ugandan") || text.contains("kampala")) return "UGA";
        if (text.contains("rwanda") || text.contains("rwandan") || text.contains("kigali")) return "RWA";
        if (text.contains("burundi") || text.contains("burundian")) return "BDI";
        if (text.contains("south sudan")) return "SSD";
        if (text.contains("sudan") && !text.contains("south sudan")) return "SDN";
        if (text.contains("ethiopia") || text.contains("ethiopian") || text.contains("tigray") || text.contains("addis ababa")) return "ETH";
        if (text.contains("somalia") || text.contains("somali") || text.contains("mogadishu")) return "SOM";
        if (text.contains("eritrea") || text.contains("eritrean")) return "ERI";
        if (text.contains("djibouti")) return "DJI";
        // Central Africa
        if (text.contains("congo") || text.contains("drc") || text.contains("kinshasa") || text.contains("goma")) return "COD";
        if (text.contains("central african republic") || text.contains("bangui")) return "CAF";
        if (text.contains("cameroon") || text.contains("cameroonian")) return "CMR";
        if (text.contains("chad") || text.contains("chadian") || text.contains("n'djamena")) return "TCD";
        // West Africa
        if (text.contains("nigeria") || text.contains("nigerian") || text.contains("lagos") || text.contains("abuja")) return "NGA";
        if (text.contains("mali") || text.contains("malian") || text.contains("bamako")) return "MLI";
        if (text.contains("burkina faso") || text.contains("burkinabe") || text.contains("ouagadougou")) return "BFA";
        if (text.contains("niger") && !text.contains("nigeria")) return "NER";
        if (text.contains("senegal") || text.contains("senegalese") || text.contains("dakar")) return "SEN";
        if (text.contains("ghana") || text.contains("ghanaian") || text.contains("accra")) return "GHA";
        // Southern/Other Africa
        if (text.contains("mozambique") || text.contains("mozambican") || text.contains("maputo") || text.contains("cabo delgado")) return "MOZ";
        if (text.contains("south africa") || text.contains("johannesburg") || text.contains("cape town")) return "ZAF";
        if (text.contains("zimbabwe") || text.contains("zimbabwean") || text.contains("harare")) return "ZWE";
        if (text.contains("libya") || text.contains("libyan") || text.contains("tripoli")) return "LBY";

        // ========== EUROPE (must be detected to prevent LAC false positives) ==========
        if (text.contains("ukraine") || text.contains("ukrainian") || text.contains("kyiv") || text.contains("kharkiv")) return "UKR";
        if (text.contains("hungary") || text.contains("hungarian") || text.contains("budapest")) return "HUN";
        if (text.contains("poland") || text.contains("polish") || text.contains("warsaw")) return "POL";
        if (text.contains("germany") || text.contains("german") || text.contains("berlin")) return "DEU";
        if (text.contains("france") || text.contains("french") || text.contains("paris")) return "FRA";
        if (text.contains("italy") || text.contains("italian") || text.contains("rome") || text.contains("lampedusa")) return "ITA";
        if (text.contains("spain") || text.contains("spanish") || text.contains("madrid") || text.contains("canary islands")) return "ESP";
        if (text.contains("greece") || text.contains("greek") || text.contains("athens") || text.contains("lesbos")) return "GRC";
        if (text.contains("serbia") || text.contains("serbian") || text.contains("belgrade")) return "SRB";
        if (text.contains("bosnia") || text.contains("bosnian") || text.contains("sarajevo")) return "BIH";
        if (text.contains("croatia") || text.contains("croatian") || text.contains("zagreb")) return "HRV";
        if (text.contains("romania") || text.contains("romanian") || text.contains("bucharest")) return "ROU";
        if (text.contains("bulgaria") || text.contains("bulgarian") || text.contains("sofia")) return "BGR";
        if (text.contains("turkey") || text.contains("turkish") || text.contains("ankara") || text.contains("istanbul")) return "TUR";
        if (text.contains("russia") || text.contains("russian") || text.contains("moscow")) return "RUS";
        if (text.contains("belarus") || text.contains("belarusian") || text.contains("minsk")) return "BLR";
        if (text.contains("moldova") || text.contains("moldovan") || text.contains("chisinau")) return "MDA";

        // ========== MENA ==========
        if (text.contains("syria") || text.contains("syrian") || text.contains("damascus") || text.contains("idlib")) return "SYR";
        if (text.contains("yemen") || text.contains("yemeni") || text.contains("houthi") || text.contains("sanaa")) return "YEM";
        if (text.contains("gaza") || text.contains("palestine") || text.contains("palestinian") || text.contains("west bank") || text.contains("rafah")) return "PSE";
        if (text.contains("lebanon") || text.contains("lebanese") || text.contains("beirut") || text.contains("hezbollah")) return "LBN";
        if (text.contains("iraq") || text.contains("iraqi") || text.contains("baghdad")) return "IRQ";
        if (text.contains("israel") || text.contains("israeli") || text.contains("tel aviv") || text.contains("jerusalem")) return "ISR";
        if (text.contains("jordan") || text.contains("jordanian") || text.contains("amman")) return "JOR";
        if (text.contains("egypt") || text.contains("egyptian") || text.contains("cairo")) return "EGY";
        if (text.contains("iran") || text.contains("iranian") || text.contains("tehran")) return "IRN";
        if (text.contains("saudi") || text.contains("riyadh")) return "SAU";

        // ========== ASIA ==========
        if (text.contains("afghanistan") || text.contains("afghan") || text.contains("kabul") || text.contains("taliban")) return "AFG";
        if (text.contains("pakistan") || text.contains("pakistani") || text.contains("islamabad") || text.contains("karachi")) return "PAK";
        if (text.contains("bangladesh") || text.contains("bangladeshi") || text.contains("dhaka") || text.contains("cox's bazar")) return "BGD";
        if (text.contains("myanmar") || text.contains("burma") || text.contains("burmese") || text.contains("rohingya") || text.contains("yangon")) return "MMR";
        if (text.contains("india") || text.contains("indian") || text.contains("delhi") || text.contains("mumbai")) return "IND";
        if (text.contains("nepal") || text.contains("nepalese") || text.contains("kathmandu")) return "NPL";
        if (text.contains("sri lanka") || text.contains("sri lankan") || text.contains("colombo")) return "LKA";
        if (text.contains("thailand") || text.contains("thai") || text.contains("bangkok")) return "THA";
        if (text.contains("indonesia") || text.contains("indonesian") || text.contains("jakarta")) return "IDN";
        if (text.contains("philippines") || text.contains("filipino") || text.contains("manila")) return "PHL";
        if (text.contains("china") || text.contains("chinese") || text.contains("beijing")) return "CHN";

        // ========== LAC (Latin America & Caribbean) ==========
        if (text.contains("venezuela") || text.contains("venezuelan") || text.contains("caracas") || text.contains("maduro")) return "VEN";
        if (text.contains("colombia") || text.contains("colombian") || text.contains("bogota") || text.contains("petro")) return "COL";
        if (text.contains("haiti") || text.contains("haitian") || text.contains("port-au-prince")) return "HTI";
        if (text.contains("guatemala") || text.contains("guatemalan")) return "GTM";
        if (text.contains("honduras") || text.contains("honduran") || text.contains("tegucigalpa")) return "HND";
        if (text.contains("el salvador") || text.contains("salvadoran") || text.contains("bukele")) return "SLV";
        if (text.contains("nicaragua") || text.contains("nicaraguan") || text.contains("managua")) return "NIC";
        if (text.contains("mexico") || text.contains("mexican") || text.contains("amlo") || text.contains("sheinbaum")) return "MEX";
        if (text.contains("peru") || text.contains("peruvian") || text.contains("lima")) return "PER";
        if (text.contains("ecuador") || text.contains("ecuadorian") || text.contains("quito")) return "ECU";
        if (text.contains("cuba") || text.contains("cuban") || text.contains("havana")) return "CUB";
        if (text.contains("darien") || text.contains("panama") || text.contains("darien gap")) return "PAN";
        if (text.contains("brazil") || text.contains("brazilian") || text.contains("brasilia") || text.contains("sao paulo")) return "BRA";
        if (text.contains("argentina") || text.contains("argentine") || text.contains("buenos aires")) return "ARG";
        if (text.contains("chile") || text.contains("chilean") || text.contains("santiago")) return "CHL";
        if (text.contains("bolivia") || text.contains("bolivian") || text.contains("la paz")) return "BOL";
        if (text.contains("dominican") || text.contains("santo domingo")) return "DOM";
        if (text.contains("costa rica") || text.contains("costa rican") || text.contains("san jose")) return "CRI";

        // ========== NORTH AMERICA ==========
        if (text.contains("united states") || text.contains("washington d.c.") ||
            text.contains("u.s. ") ||
            (text.contains("us ") && (text.contains("immigration") || text.contains("border") || text.contains("deportation"))) ||
            (text.contains("ice ") && text.contains("detention")) ||
            text.contains("cbp one") || text.contains("border patrol")) return "USA";
        if (text.contains("canada") || text.contains("canadian") || text.contains("ottawa") || text.contains("toronto")) return "CAN";

        // US-Mexico border stories → assign to MEX (LAC context)
        if (text.contains("tapachula") || text.contains("ciudad juarez") || text.contains("tijuana") ||
            text.contains("reynosa") || text.contains("matamoros") || text.contains("nogales")) return "MEX";

        return null; // Unknown country
    }

    /**
     * Detect region directly from text when no specific country is found.
     * Used as fallback for headlines like "Europe migration report" or "Africa humanitarian update".
     */
    private String detectRegionFromText(String text) {
        // Europe indicators (must be before Africa check due to "african" vs "south african")
        if (text.contains("europe") || text.contains("european") || text.contains("eu ") ||
            text.contains("schengen") || text.contains("frontex") || text.contains("mediterranean route") ||
            text.contains("balkan route") || text.contains("western balkans")) {
            return "Europe";
        }
        // Africa indicators
        if (text.contains("africa") || text.contains("african") || text.contains("sahel") ||
            text.contains("horn of africa") || text.contains("east africa") || text.contains("west africa") ||
            text.contains("sub-saharan") || text.contains("au ") || text.contains("african union")) {
            return "Africa";
        }
        // Asia indicators
        if (text.contains("asia") || text.contains("asian") || text.contains("southeast asia") ||
            text.contains("south asia") || text.contains("asean")) {
            return "Asia";
        }
        // MENA indicators
        if (text.contains("middle east") || text.contains("mena") || text.contains("gulf states") ||
            text.contains("arab") || text.contains("levant")) {
            return "MENA";
        }
        // LAC indicators (last - more specific terms already handled by country detection)
        if (text.contains("latin america") || text.contains("central america") || text.contains("caribbean") ||
            text.contains("south america") || text.contains("lac ") || text.contains("americas")) {
            return "LAC";
        }
        // North America indicators
        if (text.contains("north america") || text.contains("north american") ||
            text.contains("united states") || text.contains("u.s.") || text.contains("american")) {
            return "North America";
        }
        return null;
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

    // RSS XML DTOs
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

    /**
     * Cluster raw headlines into Story objects
     */
    private List<Story> clusterIntoStories(List<RawHeadline> headlines) {
        Map<String, List<RawHeadline>> clusters = new LinkedHashMap<>();

        for (RawHeadline h : headlines) {
            String clusterKey = findClusterKey(h, clusters.keySet());
            clusters.computeIfAbsent(clusterKey, k -> new ArrayList<>()).add(h);
        }

        List<Story> stories = new ArrayList<>();
        for (Map.Entry<String, List<RawHeadline>> entry : clusters.entrySet()) {
            Story story = buildStoryFromCluster(entry.getValue());
            if (story != null) {
                stories.add(story);
            }
        }

        return stories;
    }

    /**
     * Find or create cluster key for a headline
     * Uses detected country/region to prevent cross-region clustering
     */
    private String findClusterKey(RawHeadline h, Set<String> existingKeys) {
        String titleLower = h.title.toLowerCase();
        Set<String> titleWords = extractKeywords(titleLower);

        // Detect country from title if not set in metadata
        String effectiveCountry = h.country;
        if (effectiveCountry == null) {
            effectiveCountry = detectCountryFromText(titleLower);
        }

        // Get region for this headline (prioritize country-based, then direct region detection)
        String headlineRegion;
        if (effectiveCountry != null) {
            // First check our local map (has more countries)
            headlineRegion = COUNTRY_TO_REGION.getOrDefault(effectiveCountry,
                MonitoredCountries.getRegion(effectiveCountry));
        } else {
            // No country detected - try direct region detection from keywords
            headlineRegion = detectRegionFromText(titleLower);
            if (headlineRegion == null) {
                headlineRegion = "Global";
            }
        }

        // Try to match with existing cluster - but ONLY if same region
        for (String key : existingKeys) {
            // Extract region from cluster key (format: "REGION:country:phrase")
            String[] parts = key.split(":", 3);
            String clusterRegion = parts.length > 0 ? parts[0] : "Global";

            // Only consider clusters from the same region
            if (!headlineRegion.equals(clusterRegion)) {
                continue;
            }

            Set<String> keyWords = extractKeywords(key.toLowerCase());
            double similarity = jaccardSimilarity(titleWords, keyWords);
            if (similarity > 0.35) { // 35% word overlap = same story (slightly stricter)
                return key;
            }
        }

        // Create new cluster with region + country + key phrase
        String countryPart = (effectiveCountry != null) ? effectiveCountry : "UNK";
        return headlineRegion + ":" + countryPart + ":" + extractKeyPhrase(h.title);
    }

    /**
     * Build Story from cluster of headlines
     */
    private Story buildStoryFromCluster(List<RawHeadline> cluster) {
        if (cluster.isEmpty()) return null;

        // Pick best headline (longest, most descriptive)
        RawHeadline best = cluster.stream()
                .max(Comparator.comparingInt(h -> h.title.length()))
                .orElse(cluster.get(0));

        // Collect unique sources
        Set<String> sources = cluster.stream()
                .map(h -> h.source)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Collect countries from headline metadata AND detect from ALL titles
        Set<String> countries = new LinkedHashSet<>();

        for (RawHeadline h : cluster) {
            // First try metadata
            if (h.country != null) {
                countries.add(h.country);
            } else {
                // Detect from title text
                String detected = detectCountryFromText(h.title.toLowerCase());
                if (detected != null) {
                    countries.add(detected);
                }
            }
        }

        // Determine region from detected countries AND direct region keywords
        // Only assign specific region if MOST headlines are from the same region
        String region = "Global";

        // Count headlines per region (using country detection + direct region detection)
        Map<String, Long> regionCounts = cluster.stream()
                .map(h -> {
                    String titleLower = h.title.toLowerCase();
                    String country = h.country != null ? h.country : detectCountryFromText(titleLower);
                    if (country != null) {
                        // Use our comprehensive map first, then fallback to MonitoredCountries
                        return COUNTRY_TO_REGION.getOrDefault(country, MonitoredCountries.getRegion(country));
                    } else {
                        // No country detected - try direct region detection
                        String directRegion = detectRegionFromText(titleLower);
                        return directRegion != null ? directRegion : "Global";
                    }
                })
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));

        // Find the dominant region (must be > 50% of headlines)
        long total = cluster.size();
        for (Map.Entry<String, Long> entry : regionCounts.entrySet()) {
            if (!entry.getKey().equals("Global") && entry.getValue() > total / 2) {
                region = entry.getKey();
                break;
            }
        }

        // If no dominant region found but we have exactly 1 non-Global region, use it
        // (prevents single-headline clusters from staying Global when country is detected)
        if (region.equals("Global")) {
            List<String> nonGlobalRegions = regionCounts.keySet().stream()
                    .filter(r -> !r.equals("Global"))
                    .collect(Collectors.toList());
            if (nonGlobalRegions.size() == 1) {
                region = nonGlobalRegions.get(0);
            }
        }

        // Detect topics
        List<String> topics = detectTopics(best.title);

        // Build top headlines list
        List<Headline> topHeadlines = cluster.stream()
                .limit(3)
                .map(h -> Headline.builder()
                        .title(h.title)
                        .url(h.url)
                        .source(h.source)
                        .date(h.date)
                        .build())
                .collect(Collectors.toList());

        // Country names (use shared config)
        List<String> countryNames = countries.stream()
                .map(MonitoredCountries::getName)
                .collect(Collectors.toList());

        // Generate story ID
        String id = generateStoryId(best.title, countries);

        // Determine story type
        String storyType = cluster.size() >= 5 ? "BREAKING" :
                          cluster.size() >= 3 ? "DEVELOPING" : "UPDATE";

        return Story.builder()
                .id(id)
                .title(cleanTitle(best.title))
                .topicTags(topics)
                .region(region)
                .countries(new ArrayList<>(countries))
                .countryNames(countryNames)
                .sources(new ArrayList<>(sources))
                .firstSeen(Instant.now().minus(1, ChronoUnit.DAYS))
                .lastSeen(Instant.now())
                .volume24h(cluster.size())
                .topHeadlines(topHeadlines)
                .storyType(storyType)
                .build();
    }

    /**
     * Detect topics from title
     */
    private List<String> detectTopics(String title) {
        if (title == null) return List.of();
        String lower = title.toLowerCase();

        List<String> detected = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : TOPIC_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword)) {
                    detected.add(entry.getKey());
                    break;
                }
            }
        }

        return detected.isEmpty() ? List.of("general") : detected;
    }

    /**
     * Extract keywords from text
     */
    private Set<String> extractKeywords(String text) {
        if (text == null) return Collections.emptySet();
        // Remove common words and punctuation
        String cleaned = text.replaceAll("[^a-zA-Z\\s]", " ").toLowerCase();
        Set<String> stopwords = Set.of("the", "a", "an", "in", "on", "at", "to", "for", "of", "and", "or", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do", "does", "did", "will", "would", "could", "should", "may", "might", "must", "shall", "can", "need", "dare", "ought", "used", "that", "this", "these", "those", "what", "which", "who", "whom", "whose", "where", "when", "why", "how", "all", "each", "every", "both", "few", "more", "most", "other", "some", "such", "no", "nor", "not", "only", "own", "same", "so", "than", "too", "very", "just", "also", "now", "here", "there", "then", "once", "if", "as", "by", "from", "with", "about", "into", "through", "during", "before", "after", "above", "below", "between", "under", "again", "further", "but", "up", "down", "out", "off", "over", "says", "said", "report", "reports", "news");

        return Arrays.stream(cleaned.split("\\s+"))
                .filter(w -> w.length() > 2 && !stopwords.contains(w))
                .collect(Collectors.toSet());
    }

    /**
     * Extract key phrase from title
     */
    private String extractKeyPhrase(String title) {
        Set<String> keywords = extractKeywords(title);
        return keywords.stream().limit(3).collect(Collectors.joining("-"));
    }

    /**
     * Jaccard similarity between two sets
     */
    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    /**
     * Generate unique story ID
     */
    private String generateStoryId(String title, Set<String> countries) {
        String combined = title + String.join("", countries);
        return Integer.toHexString(combined.hashCode());
    }

    /**
     * Clean title for display
     */
    private String cleanTitle(String title) {
        if (title == null) return "";
        // Remove trailing "..." and extra whitespace
        return title.replaceAll("\\.{3,}$", "").trim();
    }

    /**
     * Internal class for raw GDELT headline
     */
    private static class RawHeadline {
        String title;
        String url;
        String source;
        String date;
        String country;
    }

    // ============================================================
    // NEWS FEED — Two-column layout (ReliefWeb | Media)
    // ============================================================

    // BBC regional feeds (from region-mapping.json) for balanced global coverage
    private static final Map<String, String> BBC_REGIONAL_FEEDS = Map.of(
        "Africa", "https://feeds.bbci.co.uk/news/world/africa/rss.xml",
        "MENA", "https://feeds.bbci.co.uk/news/world/middle_east/rss.xml",
        "Asia", "https://feeds.bbci.co.uk/news/world/asia/rss.xml",
        "LAC", "https://feeds.bbci.co.uk/news/world/latin_america/rss.xml",
        "Europe", "https://feeds.bbci.co.uk/news/world/europe/rss.xml",
        "North America", "https://feeds.bbci.co.uk/news/world/us_and_canada/rss.xml"
    );

    // Media RSS feeds for news feed (excluding ReliefWeb RSS to avoid duplication with column 1)
    private static final Map<String, String> MEDIA_RSS_FEEDS = Map.ofEntries(
        // Global media
        Map.entry("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"),
        // BBC regional (all 6 regions)
        Map.entry("BBC Africa", "https://feeds.bbci.co.uk/news/world/africa/rss.xml"),
        Map.entry("BBC Middle East", "https://feeds.bbci.co.uk/news/world/middle_east/rss.xml"),
        Map.entry("BBC Asia", "https://feeds.bbci.co.uk/news/world/asia/rss.xml"),
        Map.entry("BBC Latin America", "https://feeds.bbci.co.uk/news/world/latin_america/rss.xml"),
        Map.entry("BBC Europe", "https://feeds.bbci.co.uk/news/world/europe/rss.xml"),
        Map.entry("BBC US & Canada", "https://feeds.bbci.co.uk/news/world/us_and_canada/rss.xml"),
        // UN/Humanitarian
        Map.entry("UN News", "https://news.un.org/feed/subscribe/en/news/all/rss.xml"),
        Map.entry("UN News Migration", "https://news.un.org/feed/subscribe/en/news/topic/migrants-and-refugees/feed/rss.xml"),
        Map.entry("ICRC", "https://www.icrc.org/en/rss/news"),
        // Migration-specific
        Map.entry("IOM News", "https://www.iom.int/news/rss.xml"),
        Map.entry("MMC", "https://mixedmigration.org/feed/"),
        // LAC specialized
        Map.entry("InSight Crime", "https://insightcrime.org/feed/"),
        Map.entry("The Guardian Americas", "https://www.theguardian.com/world/americas/rss")
    );

    // Map BBC source names to their region for fast filtering
    private static final Map<String, String> SOURCE_REGION_HINT = Map.of(
        "BBC Africa", "Africa",
        "BBC Middle East", "MENA",
        "BBC Asia", "Asia",
        "BBC Latin America", "LAC",
        "BBC Europe", "Europe",
        "BBC US & Canada", "North America"
    );

    // ReliefWeb theme → topic mapping for filtering
    private static final Map<String, List<String>> THEME_TO_TOPIC = Map.of(
        "conflict", List.of("Peacekeeping and Peacebuilding", "Mine Action", "Protection and Human Rights"),
        "migration", List.of("Camp Coordination and Camp Management", "Shelter and Non-Food Items", "Camp Coordination/Management"),
        "food", List.of("Food and Nutrition", "Logistics and Telecommunications"),
        "climate", List.of("Climate Change and Environment"),
        "health", List.of("Health", "Water Sanitation Hygiene", "HIV/Aids", "Water, Sanitation and Hygiene"),
        "humanitarian", List.of("Coordination", "Disaster Management", "Recovery and Reconstruction")
    );

    // GDELT topic keywords for query parameter
    private static final Map<String, String> GDELT_TOPIC_QUERIES = Map.of(
        "conflict", "(conflict OR violence OR military OR attack OR war OR fighting)",
        "migration", "(migration OR refugee OR displaced OR asylum OR border)",
        "food", "(hunger OR famine OR food OR malnutrition OR drought)",
        "climate", "(flood OR cyclone OR earthquake OR drought OR disaster OR climate)",
        "health", "(outbreak OR epidemic OR cholera OR disease OR health)",
        "humanitarian", "(humanitarian OR crisis OR emergency OR aid OR relief)"
    );

    /**
     * News Feed response DTO
     */
    @Data
    public static class NewsFeedData {
        private List<NewsItem> reliefweb = new ArrayList<>();
        private List<NewsItem> media = new ArrayList<>();
        private Map<String, Object> meta = new HashMap<>();
    }

    /**
     * Get two-column news feed data.
     * Column 1: ReliefWeb humanitarian reports
     * Column 2: GDELT headlines + RSS feeds
     *
     * Both filtered by region and topic.
     */
    @Cacheable(value = "newsFeed", key = "(#region?.toLowerCase() ?: '') + '-' + (#topic?.toLowerCase() ?: '')")
    public NewsFeedData getNewsFeed(String region, String topic) {
        log.info("Building news feed: region={}, topic={}", region, topic);

        NewsFeedData data = new NewsFeedData();
        data.getMeta().put("region", region != null ? region : "all");
        data.getMeta().put("topic", topic != null ? topic : "all");
        data.getMeta().put("lastUpdated", java.time.Instant.now().toString());

        try {
            // Column 1: ReliefWeb
            List<NewsItem> reliefWebItems = fetchReliefWebForNewsFeed(region, topic);
            data.setReliefweb(reliefWebItems);
            log.info("News feed: {} ReliefWeb items", reliefWebItems.size());

            // Column 2: Media (GDELT + RSS)
            List<NewsItem> mediaItems = fetchMediaForNewsFeed(region, topic);
            data.setMedia(mediaItems);
            log.info("News feed: {} media items", mediaItems.size());

        } catch (Exception e) {
            log.error("Error building news feed: {}", e.getMessage(), e);
        }

        return data;
    }

    /**
     * Fetch ReliefWeb reports as NewsItem list for the news feed.
     */
    private List<NewsItem> fetchReliefWebForNewsFeed(String region, String topic) {
        List<NewsItem> items = new ArrayList<>();

        // Determine which countries to fetch based on region
        List<String> countries;
        if (region != null && !region.isBlank()) {
            countries = getCountriesForRegion(region);
        } else {
            // All priority countries
            countries = List.of(
                "SDN", "SSD", "ETH", "SOM", "COD", "NGA", "MLI", "BFA",
                "SYR", "YEM", "PSE", "LBN", "IRQ",
                "HTI", "VEN", "COL", "GTM", "HND", "MEX", "NIC",
                "AFG", "MMR", "BGD", "PAK",
                "UKR",
                "USA", "CAN"
            );
        }

        for (String iso3 : countries) {
            try {
                var reports = reliefWebService.getLatestReports(iso3, 3);
                if (reports != null) {
                    for (var report : reports) {
                        if (report.getTitle() == null || report.getTitle().isBlank()) continue;

                        // Topic filtering via themes
                        List<String> detectedTopics = detectTopicsFromThemes(report.getThemes(), report.getTitle());
                        if (topic != null && !topic.isBlank()) {
                            if (!detectedTopics.contains(topic.toLowerCase())) continue;
                        }

                        String countryRegion = COUNTRY_TO_REGION.getOrDefault(iso3,
                                MonitoredCountries.getRegion(iso3));

                        items.add(NewsItem.builder()
                                .title(report.getTitle())
                                .url(report.getUrl())
                                .source(report.getSource() != null ? report.getSource() : "ReliefWeb")
                                .sourceType("RELIEFWEB")
                                .country(iso3)
                                .countryName(MonitoredCountries.getName(iso3))
                                .region(countryRegion != null ? countryRegion : "Global")
                                .topics(detectedTopics)
                                .timeAgo(formatTimeAgo(report.getDate()))
                                .format(report.getFormat())
                                .build());
                    }
                }
            } catch (Exception e) {
                log.debug("Error fetching ReliefWeb for {}: {}", iso3, e.getMessage());
            }
        }

        // Sort by most recent first (items with "Just now" or "Xh ago" before "Xd ago")
        items.sort((a, b) -> compareTimeAgo(a.getTimeAgo(), b.getTimeAgo()));

        // Limit to 25
        if (items.size() > 25) {
            items = new ArrayList<>(items.subList(0, 25));
        }

        return items;
    }

    /**
     * Fetch media items (GDELT + RSS) as NewsItem list for the news feed.
     */
    private List<NewsItem> fetchMediaForNewsFeed(String region, String topic) {
        List<NewsItem> items = new ArrayList<>();

        // 1. GDELT regional headlines
        List<NewsItem> gdeltItems = fetchGdeltForNewsFeed(region, topic);
        items.addAll(gdeltItems);
        log.info("News feed media: {} GDELT items", gdeltItems.size());

        // 2. RSS feeds
        List<NewsItem> rssItems = fetchRssForNewsFeed(region, topic);
        items.addAll(rssItems);
        log.info("News feed media: {} RSS items", rssItems.size());

        // Deduplicate by title similarity
        items = deduplicateItems(items);

        // Sort by relevance: items with detected country first, then by source quality
        items.sort((a, b) -> {
            // Prioritize items with known country over "unknown"
            boolean aHasCountry = a.getCountry() != null && !a.getCountry().isEmpty();
            boolean bHasCountry = b.getCountry() != null && !b.getCountry().isEmpty();
            if (aHasCountry != bHasCountry) return bHasCountry ? 1 : -1;
            // Then by time
            return compareTimeAgo(a.getTimeAgo(), b.getTimeAgo());
        });

        // Limit to 30
        if (items.size() > 30) {
            items = new ArrayList<>(items.subList(0, 30));
        }

        return items;
    }

    /**
     * Fetch GDELT headlines for a region with URLs via regional OR query.
     */
    private List<NewsItem> fetchGdeltForNewsFeed(String region, String topic) {
        List<NewsItem> items = new ArrayList<>();

        try {
            // Build topic query for GDELT
            String topicQuery = null;
            if (topic != null && !topic.isBlank()) {
                topicQuery = GDELT_TOPIC_QUERIES.get(topic.toLowerCase());
            }

            if (region != null && !region.isBlank()) {
                // Single region query
                List<Headline> headlines = gdeltService.getRegionHeadlines(region, topicQuery, 15);
                for (Headline h : headlines) {
                    String country = detectCountryFromText(h.getTitle() != null ? h.getTitle().toLowerCase() : "");
                    String countryRegion = country != null ?
                            COUNTRY_TO_REGION.getOrDefault(country, MonitoredCountries.getRegion(country)) : region;

                    items.add(NewsItem.builder()
                            .title(h.getTitle())
                            .url(h.getUrl())
                            .source(h.getSource() != null ? h.getSource() : "GDELT")
                            .sourceType("GDELT")
                            .country(country)
                            .countryName(country != null ? MonitoredCountries.getName(country) : null)
                            .region(countryRegion != null ? countryRegion : region)
                            .topics(h.getTitle() != null ? detectTopics(h.getTitle()) : List.of())
                            .build());
                }
            } else {
                // All regions: query each
                for (String r : List.of("Africa", "MENA", "Asia", "LAC", "Europe", "North America")) {
                    try {
                        List<Headline> headlines = gdeltService.getRegionHeadlines(r, topicQuery, 5);
                        for (Headline h : headlines) {
                            String country = detectCountryFromText(h.getTitle() != null ? h.getTitle().toLowerCase() : "");

                            items.add(NewsItem.builder()
                                    .title(h.getTitle())
                                    .url(h.getUrl())
                                    .source(h.getSource() != null ? h.getSource() : "GDELT")
                                    .sourceType("GDELT")
                                    .country(country)
                                    .countryName(country != null ? MonitoredCountries.getName(country) : null)
                                    .region(r)
                                    .topics(h.getTitle() != null ? detectTopics(h.getTitle()) : List.of())
                                    .build());
                        }
                    } catch (Exception e) {
                        log.debug("Error fetching GDELT for region {}: {}", r, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error fetching GDELT for news feed: {}", e.getMessage());
        }

        return items;
    }

    /**
     * Fetch RSS feed headlines for the news feed.
     * Uses expanded feed list with BBC regional feeds.
     */
    private List<NewsItem> fetchRssForNewsFeed(String region, String topic) {
        List<NewsItem> items = new ArrayList<>();
        WebClient webClient = webClientBuilder.build();
        XmlMapper xmlMapper = new XmlMapper();
        xmlMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        for (Map.Entry<String, String> feed : MEDIA_RSS_FEEDS.entrySet()) {
            String sourceName = feed.getKey();
            String feedUrl = feed.getValue();

            // If region filter is set, skip BBC feeds for other regions
            if (region != null && !region.isBlank()) {
                String feedRegion = SOURCE_REGION_HINT.get(sourceName);
                if (feedRegion != null && !feedRegion.equalsIgnoreCase(region)) {
                    continue; // Skip this feed, it's for a different region
                }
            }

            try {
                String xml = webClient.get()
                    .uri(feedUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(8))
                    .block();

                if (xml == null || xml.isEmpty()) continue;

                RssFeed rssFeed = xmlMapper.readValue(xml, RssFeed.class);
                if (rssFeed == null || rssFeed.getChannel() == null || rssFeed.getChannel().getItems() == null) continue;

                int count = 0;
                for (RssItem item : rssFeed.getChannel().getItems()) {
                    if (count >= 10) break; // Max 10 items per feed
                    if (item.getTitle() == null || item.getTitle().isBlank()) continue;

                    String titleClean = cleanHtml(item.getTitle());
                    String titleLower = titleClean.toLowerCase();
                    String descLower = item.getDescription() != null ? cleanHtml(item.getDescription()).toLowerCase() : "";
                    String combined = titleLower + " " + descLower;

                    // Detect country and region
                    String country = detectCountryFromText(combined);
                    String itemRegion;
                    if (country != null) {
                        itemRegion = COUNTRY_TO_REGION.getOrDefault(country, MonitoredCountries.getRegion(country));
                    } else {
                        // Use source hint for BBC feeds, or detect from text
                        itemRegion = SOURCE_REGION_HINT.get(sourceName);
                        if (itemRegion == null) {
                            itemRegion = detectRegionFromText(combined);
                        }
                    }

                    // Region filter
                    if (region != null && !region.isBlank()) {
                        if (itemRegion == null || !itemRegion.equalsIgnoreCase(region)) continue;
                    }

                    // Topic detection and filter
                    List<String> detectedTopics = detectTopics(titleClean);
                    if (topic != null && !topic.isBlank()) {
                        if (!detectedTopics.contains(topic.toLowerCase())) continue;
                    }

                    // Crisis relevance filter for BBC feeds: skip articles with no crisis topics
                    // BBC regional feeds include sports, entertainment, business - filter to crisis content only
                    if (sourceName.startsWith("BBC") && detectedTopics.isEmpty() && country == null) {
                        continue; // Skip non-crisis BBC articles
                    }

                    items.add(NewsItem.builder()
                            .title(titleClean)
                            .url(item.getLink())
                            .source(sourceName)
                            .sourceType("RSS")
                            .country(country)
                            .countryName(country != null ? MonitoredCountries.getName(country) : null)
                            .region(itemRegion != null ? itemRegion : "Global")
                            .topics(detectedTopics)
                            .timeAgo(formatRssDate(item.getPubDate()))
                            .build());

                    count++;
                }
            } catch (Exception e) {
                log.debug("Error fetching RSS from {}: {}", sourceName, e.getMessage());
            }
        }

        return items;
    }

    /**
     * Detect topics from ReliefWeb themes + title keywords.
     */
    private List<String> detectTopicsFromThemes(List<String> themes, String title) {
        Set<String> topics = new LinkedHashSet<>();

        // Match themes
        if (themes != null) {
            for (Map.Entry<String, List<String>> entry : THEME_TO_TOPIC.entrySet()) {
                for (String theme : themes) {
                    if (entry.getValue().stream().anyMatch(t -> theme.toLowerCase().contains(t.toLowerCase()))) {
                        topics.add(entry.getKey());
                        break;
                    }
                }
            }
        }

        // Also check title keywords (catches topics not in themes)
        if (title != null) {
            topics.addAll(detectTopics(title));
        }

        return topics.isEmpty() ? List.of("humanitarian") : new ArrayList<>(topics);
    }

    /**
     * Get crisis countries for a specific region.
     */
    private List<String> getCountriesForRegion(String region) {
        if (region == null) return Collections.emptyList();

        // North America: not in CRISIS_COUNTRIES, handle separately
        if (region.equalsIgnoreCase("North America")) {
            return List.of("USA", "CAN");
        }

        List<String> countries = new ArrayList<>();
        for (String iso3 : MonitoredCountries.CRISIS_COUNTRIES) {
            String r = MonitoredCountries.getRegion(iso3);
            if (r != null && r.equalsIgnoreCase(region)) {
                countries.add(iso3);
            }
        }
        return countries;
    }

    /**
     * Deduplicate items using Jaccard similarity on title keywords.
     * Two items are considered duplicates if word overlap > 45%.
     */
    private List<NewsItem> deduplicateItems(List<NewsItem> items) {
        List<NewsItem> unique = new ArrayList<>();
        List<Set<String>> uniqueKeywords = new ArrayList<>();

        for (NewsItem item : items) {
            if (item.getTitle() == null) continue;
            Set<String> words = extractKeywords(item.getTitle().toLowerCase());
            if (words.isEmpty()) continue;

            boolean isDuplicate = false;
            for (Set<String> existingWords : uniqueKeywords) {
                if (jaccardSimilarity(words, existingWords) > 0.45) {
                    isDuplicate = true;
                    break;
                }
            }

            if (!isDuplicate) {
                unique.add(item);
                uniqueKeywords.add(words);
            }
        }
        return unique;
    }

    /**
     * Format ReliefWeb date string to "Xh ago" / "Xd ago" format.
     */
    private String formatTimeAgo(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            java.time.LocalDate date = java.time.LocalDate.parse(dateStr.substring(0, 10));
            long days = java.time.temporal.ChronoUnit.DAYS.between(date, java.time.LocalDate.now());
            if (days == 0) return "Today";
            if (days == 1) return "1d ago";
            if (days < 7) return days + "d ago";
            return date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"));
        } catch (Exception e) {
            return dateStr.length() > 10 ? dateStr.substring(0, 10) : dateStr;
        }
    }

    /**
     * Format RSS pubDate to "Xh ago" format.
     */
    private String formatRssDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return null;
        try {
            java.time.ZonedDateTime zdt = java.time.ZonedDateTime.parse(pubDate,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            long hours = java.time.Duration.between(zdt.toInstant(), java.time.Instant.now()).toHours();
            if (hours < 1) return "Just now";
            if (hours < 24) return hours + "h ago";
            long days = hours / 24;
            if (days < 7) return days + "d ago";
            return zdt.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compare two timeAgo strings for sorting (most recent first).
     */
    private int compareTimeAgo(String a, String b) {
        return Integer.compare(timeAgoToMinutes(a), timeAgoToMinutes(b));
    }

    private int timeAgoToMinutes(String timeAgo) {
        if (timeAgo == null) return Integer.MAX_VALUE;
        if (timeAgo.equals("Just now")) return 0;
        if (timeAgo.equals("Today")) return 60;
        try {
            if (timeAgo.endsWith("h ago")) {
                return Integer.parseInt(timeAgo.replace("h ago", "").trim()) * 60;
            }
            if (timeAgo.endsWith("d ago")) {
                return Integer.parseInt(timeAgo.replace("d ago", "").trim()) * 1440;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        return Integer.MAX_VALUE; // Unknown format = oldest
    }
}
