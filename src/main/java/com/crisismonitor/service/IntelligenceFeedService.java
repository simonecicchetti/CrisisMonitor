package com.crisismonitor.service;

import com.crisismonitor.model.Headline;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Centralized Intelligence Feed Service
 * Pre-aggregates all data needed for Intelligence section in a single cached call
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntelligenceFeedService {

    private final GDELTService gdeltService;
    private final ReliefWebService reliefWebService;
    private final RiskScoreService riskScoreService;

    // Use @Lazy to break circular dependency with CacheWarmupService
    @Autowired
    @Lazy
    private CacheWarmupService cacheWarmupService;

    // Top crisis countries for ReliefWeb reports
    private static final List<String> PRIORITY_COUNTRIES = Arrays.asList(
        "SDN", "SSD", "YEM", "AFG", "SOM", "ETH", "HTI", "MMR", "COD", "SYR",
        "UKR", "PSE", "NGA", "MLI", "BFA", "NER", "TCD", "CAF", "LBN", "MOZ",
        "PAK", "BGD", "COL", "VEN", "IRQ", "CMR", "LBY", "BDI", "RWA", "KEN",
        "UGA", "IRN", "GTM", "HND", "SLV", "NIC", "MEX", "PER", "ECU", "CUB", "PAN"
    );

    private static final Map<String, String> ISO3_TO_NAME = new HashMap<>();
    static {
        ISO3_TO_NAME.put("SDN", "Sudan");
        ISO3_TO_NAME.put("SSD", "South Sudan");
        ISO3_TO_NAME.put("YEM", "Yemen");
        ISO3_TO_NAME.put("AFG", "Afghanistan");
        ISO3_TO_NAME.put("SOM", "Somalia");
        ISO3_TO_NAME.put("ETH", "Ethiopia");
        ISO3_TO_NAME.put("HTI", "Haiti");
        ISO3_TO_NAME.put("MMR", "Myanmar");
        ISO3_TO_NAME.put("COD", "DR Congo");
        ISO3_TO_NAME.put("SYR", "Syria");
        ISO3_TO_NAME.put("UKR", "Ukraine");
        ISO3_TO_NAME.put("NGA", "Nigeria");
        ISO3_TO_NAME.put("MLI", "Mali");
        ISO3_TO_NAME.put("BFA", "Burkina Faso");
        ISO3_TO_NAME.put("NER", "Niger");
        ISO3_TO_NAME.put("TCD", "Chad");
        ISO3_TO_NAME.put("LBN", "Lebanon");
        ISO3_TO_NAME.put("PSE", "Palestine");
        ISO3_TO_NAME.put("PAK", "Pakistan");
        ISO3_TO_NAME.put("BGD", "Bangladesh");
        ISO3_TO_NAME.put("VEN", "Venezuela");
        ISO3_TO_NAME.put("COL", "Colombia");
        ISO3_TO_NAME.put("CAF", "CAR");
        ISO3_TO_NAME.put("MOZ", "Mozambique");
        ISO3_TO_NAME.put("LBY", "Libya");
        ISO3_TO_NAME.put("IRQ", "Iraq");
        ISO3_TO_NAME.put("IRN", "Iran");
        ISO3_TO_NAME.put("UGA", "Uganda");
        ISO3_TO_NAME.put("CMR", "Cameroon");
        ISO3_TO_NAME.put("RWA", "Rwanda");
        ISO3_TO_NAME.put("BDI", "Burundi");
        ISO3_TO_NAME.put("KEN", "Kenya");
        ISO3_TO_NAME.put("GTM", "Guatemala");
        ISO3_TO_NAME.put("HND", "Honduras");
        ISO3_TO_NAME.put("SLV", "El Salvador");
        ISO3_TO_NAME.put("NIC", "Nicaragua");
        ISO3_TO_NAME.put("MEX", "Mexico");
        ISO3_TO_NAME.put("PER", "Peru");
        ISO3_TO_NAME.put("ECU", "Ecuador");
        ISO3_TO_NAME.put("CUB", "Cuba");
        ISO3_TO_NAME.put("PAN", "Panama");
    }

    /**
     * Get complete Intelligence Feed data in a single cached call
     * This is the main endpoint for the frontend
     */
    @Cacheable(value = "intelligenceFeed", unless = "#result == null")
    public IntelligenceFeedData getIntelligenceFeed() {
        log.info("Building Intelligence Feed (will be cached)...");
        long startTime = System.currentTimeMillis();

        IntelligenceFeedData feed = new IntelligenceFeedData();
        feed.setTimestamp(LocalDateTime.now().toString());

        // 1. Get top risk country for News Signal
        try {
            var scores = riskScoreService.getAllRiskScores();
            if (scores != null && !scores.isEmpty()) {
                var topRisk = scores.get(0);
                feed.setTopRiskCountry(topRisk.getCountryName());
                feed.setTopRiskIso3(topRisk.getIso3());
                feed.setTopRiskScore(topRisk.getScore());
                feed.setTopRiskLevel(topRisk.getRiskLevel());
                feed.setTopRiskDrivers(topRisk.getDrivers());
            }
        } catch (Exception e) {
            log.warn("Failed to get top risk country: {}", e.getMessage());
        }

        // 2. Get GDELT spikes for media coverage table (only if cache is ready)
        if (cacheWarmupService.isCacheReady("conflict")) {
            try {
                var spikes = gdeltService.getAllConflictSpikes();
                if (spikes != null) {
                    List<MediaSpikeInfo> spikeList = spikes.stream()
                        .filter(s -> s.getZScore() > 0.5) // Only show elevated
                        .sorted((a, b) -> Double.compare(b.getZScore(), a.getZScore()))
                        .limit(15)
                        .map(s -> {
                            MediaSpikeInfo info = new MediaSpikeInfo();
                            info.setIso3(s.getIso3());
                            info.setCountryName(ISO3_TO_NAME.getOrDefault(s.getIso3(), s.getIso3()));
                            info.setZScore(s.getZScore());
                            info.setArticles7d(s.getArticlesLast7Days());
                            info.setSpikeLevel(s.getSpikeLevel());
                            return info;
                        })
                        .collect(Collectors.toList());
                    feed.setMediaSpikes(spikeList);
                }
            } catch (Exception e) {
                log.warn("Failed to get GDELT spikes: {}", e.getMessage());
            }
        } else {
            log.info("GDELT cache not ready - skipping media spikes for now");
            feed.setMediaSpikes(new ArrayList<>());
        }

        // 3. Skip GDELT headline fetch to avoid rate limiting
        // Headlines are now only fetched on-demand through the spike cards
        log.debug("Skipping GDELT headline fetch for IntelligenceFeed (rate limiting protection)");

        // 4. Get ReliefWeb reports for priority countries (increased limit)
        List<ReportInfo> allReports = new ArrayList<>();
        for (String iso3 : PRIORITY_COUNTRIES.subList(0, Math.min(12, PRIORITY_COUNTRIES.size()))) {
            try {
                var reports = reliefWebService.getLatestReports(iso3, 2);
                if (reports != null) {
                    for (var r : reports) {
                        ReportInfo info = new ReportInfo();
                        info.setTitle(r.getTitle());
                        info.setUrl(r.getUrl());
                        info.setSource(r.getSource());
                        info.setDate(r.getDate());
                        info.setCountryIso3(iso3);
                        info.setCountryName(ISO3_TO_NAME.getOrDefault(iso3, iso3));
                        allReports.add(info);
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to get ReliefWeb for {}: {}", iso3, e.getMessage());
            }
        }
        feed.setReliefWebReports(allReports);

        long duration = System.currentTimeMillis() - startTime;
        log.info("Intelligence Feed built in {}ms: {} spikes, {} reports, {} headlines",
            duration,
            feed.getMediaSpikes() != null ? feed.getMediaSpikes().size() : 0,
            feed.getReliefWebReports() != null ? feed.getReliefWebReports().size() : 0,
            feed.getTopRiskHeadlines() != null ? feed.getTopRiskHeadlines().size() : 0);

        feed.setStatus("READY");
        return feed;
    }

    // ========================================
    // DTOs
    // ========================================

    @Data
    public static class IntelligenceFeedData {
        private String status;
        private String timestamp;

        // Top Risk Country (News Signal)
        private String topRiskCountry;
        private String topRiskIso3;
        private int topRiskScore;
        private String topRiskLevel;
        private List<String> topRiskDrivers;
        private List<HeadlineInfo> topRiskHeadlines;

        // Media Coverage (GDELT)
        private List<MediaSpikeInfo> mediaSpikes;

        // Official Reports (ReliefWeb)
        private List<ReportInfo> reliefWebReports;
    }

    @Data
    public static class HeadlineInfo {
        private String title;
        private String url;
        private String source;
        private String countryIso3;
        private String countryName;
    }

    @Data
    public static class MediaSpikeInfo {
        private String iso3;
        private String countryName;
        private double zScore;
        private int articles7d;
        private String spikeLevel;
    }

    @Data
    public static class ReportInfo {
        private String title;
        private String url;
        private String source;
        private String date;
        private String countryIso3;
        private String countryName;
    }
}
