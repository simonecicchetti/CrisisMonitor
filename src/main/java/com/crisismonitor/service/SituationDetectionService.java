package com.crisismonitor.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.crisismonitor.config.MonitoredCountries;
import com.crisismonitor.model.Headline;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Situation Detection Engine
 *
 * Transforms raw data into actionable humanitarian situations.
 * A "Situation" is detected when:
 * 1. GDELT shows media surge (articles > threshold OR week-over-week increase)
 * 2. ReliefWeb has operational reports in last 5 days
 * 3. Content matches crisis keywords (not conferences/awards)
 *
 * Output: "What's getting worse? Where? Why?"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SituationDetectionService {

    private final GDELTService gdeltService;
    private final ReliefWebService reliefWebService;
    private final RiskScoreService riskScoreService;

    // Crisis keywords - ONLY these trigger situations
    private static final List<String> CRISIS_KEYWORDS = Arrays.asList(
        // Displacement
        "displacement", "displaced", "flee", "fleeing", "refugees", "migration", "migrants",
        "idp", "internally displaced", "exodus", "returnees",
        // Food security
        "famine", "hunger", "starvation", "malnutrition", "food insecurity", "ipc", "food crisis",
        // Violence
        "violence", "attack", "attacks", "clashes", "fighting", "conflict", "killed", "casualties",
        "airstrike", "bombing", "shelling", "massacre",
        // Access
        "access constraints", "aid suspension", "humanitarian access", "blocked", "restricted access",
        "convoy attack", "aid workers",
        // Climate
        "flood", "flooding", "drought", "cyclone", "hurricane", "earthquake", "landslide",
        // Health
        "outbreak", "epidemic", "cholera", "disease"
    );

    // Noise keywords - filter these OUT
    private static final List<String> NOISE_KEYWORDS = Arrays.asList(
        "conference", "workshop", "training", "award", "ceremony", "anniversary",
        "webinar", "symposium", "forum", "summit", "meeting minutes",
        "recruitment", "vacancy", "job opening", "tender", "procurement"
    );

    // Situation type definitions
    private static final Map<String, SituationType> SITUATION_TYPES = new LinkedHashMap<>();
    static {
        SITUATION_TYPES.put("ACCESS_CONSTRAINTS", new SituationType(
            "Access Constraints",
            Arrays.asList("access", "blocked", "restricted", "convoy attack", "aid suspension", "humanitarian access"),
            "Aid delivery under threat"
        ));
        SITUATION_TYPES.put("DISPLACEMENT_SURGE", new SituationType(
            "Displacement Surge",
            Arrays.asList("displacement", "displaced", "flee", "exodus", "refugees", "migration", "idp"),
            "Population movement increasing"
        ));
        SITUATION_TYPES.put("FOOD_CRISIS", new SituationType(
            "Food Crisis",
            Arrays.asList("famine", "hunger", "starvation", "malnutrition", "food insecurity", "ipc"),
            "Food security deteriorating"
        ));
        SITUATION_TYPES.put("VIOLENCE_ESCALATION", new SituationType(
            "Violence Escalation",
            Arrays.asList("attack", "clashes", "fighting", "killed", "casualties", "airstrike", "shelling"),
            "Conflict intensity increasing"
        ));
        SITUATION_TYPES.put("CLIMATE_SHOCK", new SituationType(
            "Climate Shock",
            Arrays.asList("flood", "drought", "cyclone", "hurricane", "earthquake", "landslide"),
            "Climate event impacting population"
        ));
        SITUATION_TYPES.put("HEALTH_EMERGENCY", new SituationType(
            "Health Emergency",
            Arrays.asList("outbreak", "epidemic", "cholera", "disease"),
            "Health crisis emerging"
        ));
    }

    // Country name mappings
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
        ISO3_TO_NAME.put("VEN", "Venezuela");
        ISO3_TO_NAME.put("COL", "Colombia");
        ISO3_TO_NAME.put("LBN", "Lebanon");
        ISO3_TO_NAME.put("PSE", "Gaza/Palestine");
        ISO3_TO_NAME.put("NGA", "Nigeria");
        ISO3_TO_NAME.put("MLI", "Mali");
        ISO3_TO_NAME.put("BFA", "Burkina Faso");
        ISO3_TO_NAME.put("NER", "Niger");
        ISO3_TO_NAME.put("TCD", "Chad");
        ISO3_TO_NAME.put("CAF", "Central African Republic");
        ISO3_TO_NAME.put("MOZ", "Mozambique");
        ISO3_TO_NAME.put("PAK", "Pakistan");
        ISO3_TO_NAME.put("BGD", "Bangladesh");
        ISO3_TO_NAME.put("LBY", "Libya");
        ISO3_TO_NAME.put("IRQ", "Iraq");
        ISO3_TO_NAME.put("IRN", "Iran");
        ISO3_TO_NAME.put("KEN", "Kenya");
        ISO3_TO_NAME.put("UGA", "Uganda");
        ISO3_TO_NAME.put("CMR", "Cameroon");
        ISO3_TO_NAME.put("RWA", "Rwanda");
        ISO3_TO_NAME.put("BDI", "Burundi");
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

    // Country name aliases for headline matching (multiple names per country)
    private static final Map<String, List<String>> COUNTRY_ALIASES = new HashMap<>();
    static {
        COUNTRY_ALIASES.put("SDN", Arrays.asList("sudan"));
        COUNTRY_ALIASES.put("SSD", Arrays.asList("south sudan", "s. sudan", "s sudan", "juba"));
        COUNTRY_ALIASES.put("YEM", Arrays.asList("yemen", "sanaa", "sana'a", "aden", "houthi"));
        COUNTRY_ALIASES.put("AFG", Arrays.asList("afghanistan", "afghan", "kabul", "taliban"));
        COUNTRY_ALIASES.put("SOM", Arrays.asList("somalia", "somali", "mogadishu"));
        COUNTRY_ALIASES.put("ETH", Arrays.asList("ethiopia", "ethiopian", "addis ababa", "tigray"));
        COUNTRY_ALIASES.put("HTI", Arrays.asList("haiti", "haitian", "port-au-prince"));
        COUNTRY_ALIASES.put("MMR", Arrays.asList("myanmar", "burma", "burmese", "yangon", "rohingya"));
        COUNTRY_ALIASES.put("COD", Arrays.asList("congo", "drc", "democratic republic of congo", "kinshasa", "goma"));
        COUNTRY_ALIASES.put("SYR", Arrays.asList("syria", "syrian", "damascus", "aleppo"));
        COUNTRY_ALIASES.put("UKR", Arrays.asList("ukraine", "ukrainian", "kyiv", "kiev"));
        COUNTRY_ALIASES.put("VEN", Arrays.asList("venezuela", "venezuelan", "caracas", "maduro"));
        COUNTRY_ALIASES.put("COL", Arrays.asList("colombia", "colombian", "bogota"));
        COUNTRY_ALIASES.put("LBN", Arrays.asList("lebanon", "lebanese", "beirut", "hezbollah"));
        COUNTRY_ALIASES.put("PSE", Arrays.asList("gaza", "palestine", "palestinian", "west bank", "rafah"));
        COUNTRY_ALIASES.put("NGA", Arrays.asList("nigeria", "nigerian", "lagos", "abuja", "boko haram"));
        COUNTRY_ALIASES.put("MLI", Arrays.asList("mali", "malian", "bamako"));
        COUNTRY_ALIASES.put("BFA", Arrays.asList("burkina faso", "burkinabe", "ouagadougou"));
        COUNTRY_ALIASES.put("NER", Arrays.asList("niger", "nigerien", "niamey"));
        COUNTRY_ALIASES.put("TCD", Arrays.asList("chad", "chadian", "n'djamena"));
        COUNTRY_ALIASES.put("CAF", Arrays.asList("central african republic", "car", "bangui"));
        COUNTRY_ALIASES.put("MOZ", Arrays.asList("mozambique", "mozambican", "maputo", "cabo delgado"));
        COUNTRY_ALIASES.put("PAK", Arrays.asList("pakistan", "pakistani", "islamabad", "karachi"));
        COUNTRY_ALIASES.put("BGD", Arrays.asList("bangladesh", "bangladeshi", "dhaka", "cox's bazar"));
        COUNTRY_ALIASES.put("LBY", Arrays.asList("libya", "libyan", "tripoli", "benghazi"));
        COUNTRY_ALIASES.put("IRQ", Arrays.asList("iraq", "iraqi", "baghdad", "mosul"));
        COUNTRY_ALIASES.put("IRN", Arrays.asList("iran", "iranian", "tehran", "irgc", "hormuz", "persian gulf"));
        COUNTRY_ALIASES.put("KEN", Arrays.asList("kenya", "kenyan", "nairobi"));
        COUNTRY_ALIASES.put("UGA", Arrays.asList("uganda", "ugandan", "kampala"));
        COUNTRY_ALIASES.put("CMR", Arrays.asList("cameroon", "cameroonian", "yaounde"));
        COUNTRY_ALIASES.put("RWA", Arrays.asList("rwanda", "rwandan", "kigali"));
        COUNTRY_ALIASES.put("BDI", Arrays.asList("burundi", "burundian", "bujumbura"));
        COUNTRY_ALIASES.put("GTM", Arrays.asList("guatemala", "guatemalan"));
        COUNTRY_ALIASES.put("HND", Arrays.asList("honduras", "honduran", "tegucigalpa"));
        COUNTRY_ALIASES.put("SLV", Arrays.asList("el salvador", "salvadoran", "salvadorean"));
        COUNTRY_ALIASES.put("NIC", Arrays.asList("nicaragua", "nicaraguan", "managua"));
        COUNTRY_ALIASES.put("MEX", Arrays.asList("mexico", "mexican", "mexico city"));
        COUNTRY_ALIASES.put("PER", Arrays.asList("peru", "peruvian", "lima"));
        COUNTRY_ALIASES.put("ECU", Arrays.asList("ecuador", "ecuadorian", "quito"));
        COUNTRY_ALIASES.put("CUB", Arrays.asList("cuba", "cuban", "havana"));
        COUNTRY_ALIASES.put("PAN", Arrays.asList("panama", "panamanian", "darien"));
    }

    /**
     * Check if headline matches the country (contains country name or alias)
     */
    private boolean headlineMatchesCountry(String headline, String iso3) {
        if (headline == null || iso3 == null) return false;
        String lowerHeadline = headline.toLowerCase();

        List<String> aliases = COUNTRY_ALIASES.get(iso3);
        if (aliases == null) return false;

        for (String alias : aliases) {
            if (lowerHeadline.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Main method: Detect active humanitarian situations
     */
    @Cacheable(value = "activeSituationsV2", unless = "#result == null")
    public SituationReport getActiveSituations() {
        log.info("Detecting active humanitarian situations...");
        long startTime = System.currentTimeMillis();

        SituationReport report = new SituationReport();
        report.setTimestamp(LocalDateTime.now().toString());
        report.setDate(java.time.LocalDate.now().toString());

        List<Situation> situations = new ArrayList<>();

        try {
            // Get GDELT spikes
            var mediaSpikes = gdeltService.getAllConflictSpikes();

            // Get risk scores for context
            var riskScores = riskScoreService.getAllRiskScores();
            Map<String, Integer> riskByCountry = new HashMap<>();
            if (riskScores != null) {
                for (var score : riskScores) {
                    riskByCountry.put(score.getIso3(), score.getScore());
                }
            }

            // Process each country with media activity
            if (mediaSpikes != null) {
                for (var spike : mediaSpikes) {
                    String iso3 = spike.getIso3();
                    String countryName = MonitoredCountries.getName(iso3);

                    // Get ReliefWeb reports for this country
                    List<ReliefWebService.HumanitarianReport> reports = new ArrayList<>();
                    try {
                        reports = reliefWebService.getLatestReports(iso3, 5);
                    } catch (Exception e) {
                        log.debug("No ReliefWeb reports for {}", iso3);
                    }

                    // Filter reports to only crisis-relevant ones
                    List<ReliefWebService.HumanitarianReport> crisisReports = filterCrisisReports(reports);

                    // Skip GDELT headline fetch to avoid rate limiting
                    // Use headlines from spike data if available
                    List<Headline> headlines = new ArrayList<>();
                    if (spike.getTopHeadlines() != null) {
                        for (String title : spike.getTopHeadlines()) {
                            headlines.add(Headline.builder()
                                .title(title)
                                .source("GDELT")
                                .build());
                        }
                    }

                    // Filter headlines to crisis-relevant ones
                    List<Headline> crisisHeadlines = filterCrisisHeadlines(headlines);

                    // Detect situation type from content
                    String situationType = detectSituationType(crisisHeadlines, crisisReports);

                    // Only create situation if:
                    // 1. Has media activity (spike level elevated+)
                    // 2. Has at least 1 crisis-relevant report OR headline
                    // 3. Detected a situation type
                    if (situationType != null &&
                        (spike.getArticlesLast7Days() > 100 || "CRITICAL".equals(spike.getSpikeLevel()) || "HIGH".equals(spike.getSpikeLevel())) &&
                        (!crisisReports.isEmpty() || !crisisHeadlines.isEmpty())) {

                        Situation situation = new Situation();
                        situation.setIso3(iso3);
                        situation.setCountryName(countryName);
                        situation.setSituationType(situationType);
                        situation.setSituationLabel(SITUATION_TYPES.get(situationType).getLabel());

                        // Build summary
                        String summary = buildSituationSummary(countryName, situationType, spike, crisisHeadlines, crisisReports);
                        situation.setSummary(summary);

                        // Store numeric values for sorting/display
                        int riskScore = riskByCountry.getOrDefault(iso3, 50);
                        int articlesCount = spike.getArticlesLast7Days();
                        int reportsCount = crisisReports.size();

                        situation.setRiskScore(riskScore);
                        situation.setArticlesCount(articlesCount);
                        situation.setReportsCount(reportsCount);

                        // Signals (kept for backward compat, but frontend uses new fields)
                        List<String> signals = new ArrayList<>();
                        signals.add(String.format("Media volume: %d articles (7 days)", articlesCount));
                        if (riskByCountry.containsKey(iso3)) {
                            signals.add(String.format("Risk score: %d", riskScore));
                        }
                        if (reportsCount > 0) {
                            signals.add(String.format("%d operational reports (ReliefWeb)", reportsCount));
                        }
                        situation.setSignals(signals);

                        // Evidence - ONLY headlines matching this country (fix Venezuela/Iran mismatch)
                        List<Evidence> evidence = new ArrayList<>();
                        List<Headline> matchingHeadlines = crisisHeadlines.stream()
                            .filter(h -> MonitoredCountries.headlineMatchesCountry(h.getTitle(), iso3))
                            .limit(5)
                            .collect(Collectors.toList());

                        for (var h : matchingHeadlines) {
                            evidence.add(new Evidence("GDELT", h.getTitle(), h.getUrl(), h.getSource()));
                        }
                        for (var r : crisisReports.stream().limit(3).collect(Collectors.toList())) {
                            evidence.add(new Evidence("ReliefWeb", r.getTitle(), r.getUrl(), r.getSource()));
                        }

                        // If no matching headlines, use spike headlines as fallback for HIGH/CRITICAL
                        if (evidence.isEmpty() &&
                            ("CRITICAL".equals(spike.getSpikeLevel()) || "HIGH".equals(spike.getSpikeLevel()))) {
                            // Use spike's top headlines as evidence (they're already country-filtered by GDELT)
                            List<String> spikeHeadlines = spike.getTopHeadlines();
                            if (spikeHeadlines != null && !spikeHeadlines.isEmpty()) {
                                for (String headline : spikeHeadlines.stream().limit(3).collect(Collectors.toList())) {
                                    evidence.add(new Evidence("GDELT", headline, null, "Media report"));
                                }
                            }
                        }

                        // Skip situations with no evidence only if LOW spike level
                        if (evidence.isEmpty()) {
                            log.debug("Skipping {} - no matching evidence and low spike level", iso3);
                            continue;
                        }

                        situation.setEvidence(evidence);

                        // Severity based on combined risk score + media intensity
                        // Boost severity if spike is CRITICAL or HIGH with high volume
                        int effectiveScore = riskScore;
                        if ("CRITICAL".equals(spike.getSpikeLevel()) && articlesCount > 200) {
                            effectiveScore = Math.max(effectiveScore, 75); // At least HIGH
                        } else if ("HIGH".equals(spike.getSpikeLevel()) && articlesCount > 150) {
                            effectiveScore = Math.max(effectiveScore, 55); // At least ELEVATED
                        }
                        String severity = effectiveScore >= 80 ? "CRITICAL" :
                                         effectiveScore >= 60 ? "HIGH" :
                                         effectiveScore >= 40 ? "ELEVATED" : "WATCH";
                        situation.setSeverity(severity);

                        situations.add(situation);
                    }
                }
            }

            // MERGE duplicates by iso3 + situationType
            Map<String, Situation> mergedMap = new LinkedHashMap<>();
            for (Situation s : situations) {
                String key = s.getIso3() + ":" + s.getSituationType();
                if (mergedMap.containsKey(key)) {
                    // Merge: combine evidence, take higher counts
                    Situation existing = mergedMap.get(key);
                    // Merge evidence (dedupe by URL)
                    Set<String> existingUrls = existing.getEvidence().stream()
                        .map(Evidence::getUrl).collect(Collectors.toSet());
                    for (Evidence e : s.getEvidence()) {
                        if (e.getUrl() != null && !existingUrls.contains(e.getUrl())) {
                            existing.getEvidence().add(e);
                            existingUrls.add(e.getUrl());
                        }
                    }
                    // Take max values
                    existing.setArticlesCount(Math.max(existing.getArticlesCount(), s.getArticlesCount()));
                    existing.setReportsCount(existing.getReportsCount() + s.getReportsCount());
                    // Update signals with merged counts
                    existing.getSignals().clear();
                    existing.getSignals().add(String.format("Media volume: %d articles (7 days)", existing.getArticlesCount()));
                    existing.getSignals().add(String.format("Risk score: %d", existing.getRiskScore()));
                    if (existing.getReportsCount() > 0) {
                        existing.getSignals().add(String.format("%d operational reports (ReliefWeb)", existing.getReportsCount()));
                    }
                } else {
                    mergedMap.put(key, s);
                }
            }
            List<Situation> mergedSituations = new ArrayList<>(mergedMap.values());

            // Sort by: severity desc, riskScore desc, articlesCount desc
            mergedSituations.sort((a, b) -> {
                int severityCompare = severityOrder(b.getSeverity()) - severityOrder(a.getSeverity());
                if (severityCompare != 0) return severityCompare;
                int riskCompare = b.getRiskScore() - a.getRiskScore();
                if (riskCompare != 0) return riskCompare;
                return b.getArticlesCount() - a.getArticlesCount();
            });

            // Enrich with trajectory and related countries
            enrichSituations(mergedSituations);

            // Limit to top 15 situations (merged)
            report.setSituations(mergedSituations.stream().limit(15).collect(Collectors.toList()));
            report.setTotalSituations(mergedSituations.size());

        } catch (Exception e) {
            log.error("Error detecting situations: {}", e.getMessage());
        }

        // Build today's summary
        report.setTodaySummary(buildTodaySummary(report.getSituations()));
        report.setStatus("READY");

        long duration = System.currentTimeMillis() - startTime;
        log.info("Detected {} active situations in {}ms", report.getSituations().size(), duration);

        return report;
    }

    /**
     * Filter reports to only crisis-relevant content
     */
    private List<ReliefWebService.HumanitarianReport> filterCrisisReports(List<ReliefWebService.HumanitarianReport> reports) {
        if (reports == null) return Collections.emptyList();

        return reports.stream()
            .filter(r -> {
                String text = (r.getTitle() + " " + (r.getSource() != null ? r.getSource() : "")).toLowerCase();

                // Check for noise keywords
                for (String noise : NOISE_KEYWORDS) {
                    if (text.contains(noise)) return false;
                }

                // Check for crisis keywords
                for (String crisis : CRISIS_KEYWORDS) {
                    if (text.contains(crisis)) return true;
                }

                // Also keep IPC, displacement tracking, situation reports
                if (text.contains("situation report") || text.contains("sitrep") ||
                    text.contains("flash update") || text.contains("emergency") ||
                    text.contains("humanitarian update") || text.contains("displacement tracking")) {
                    return true;
                }

                return false;
            })
            .collect(Collectors.toList());
    }

    /**
     * Filter headlines to only crisis-relevant content
     */
    private List<Headline> filterCrisisHeadlines(List<Headline> headlines) {
        if (headlines == null) return Collections.emptyList();

        return headlines.stream()
            .filter(h -> {
                String text = h.getTitle().toLowerCase();

                // Check for noise
                for (String noise : NOISE_KEYWORDS) {
                    if (text.contains(noise)) return false;
                }

                // Check for crisis keywords
                for (String crisis : CRISIS_KEYWORDS) {
                    if (text.contains(crisis)) return true;
                }

                return false;
            })
            .collect(Collectors.toList());
    }

    /**
     * Detect the primary situation type from content
     */
    private String detectSituationType(List<Headline> headlines, List<ReliefWebService.HumanitarianReport> reports) {
        Map<String, Integer> typeScores = new HashMap<>();

        // Score each situation type based on keyword matches
        for (var entry : SITUATION_TYPES.entrySet()) {
            String typeKey = entry.getKey();
            SituationType type = entry.getValue();
            int score = 0;

            for (var h : headlines) {
                String text = h.getTitle().toLowerCase();
                for (String keyword : type.getKeywords()) {
                    if (text.contains(keyword)) {
                        score += 2; // Headlines weighted more
                        // No break: count ALL matching keywords per headline for better type discrimination
                    }
                }
            }

            for (var r : reports) {
                String text = r.getTitle().toLowerCase();
                for (String keyword : type.getKeywords()) {
                    if (text.contains(keyword)) {
                        score += 1;
                    }
                }
            }

            if (score > 0) {
                typeScores.put(typeKey, score);
            }
        }

        // Return highest scoring type
        return typeScores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /**
     * Build human-readable situation summary
     */
    private String buildSituationSummary(String country, String situationType,
                                         com.crisismonitor.model.MediaSpike spike,
                                         List<Headline> headlines,
                                         List<ReliefWebService.HumanitarianReport> reports) {
        SituationType type = SITUATION_TYPES.get(situationType);
        if (type == null) return country + " - Humanitarian situation developing";

        // Extract key detail from headlines if available
        String detail = "";
        if (!headlines.isEmpty()) {
            String firstHeadline = headlines.get(0).getTitle().toLowerCase();
            if (firstHeadline.contains("attack")) detail = "following attacks";
            else if (firstHeadline.contains("flood")) detail = "due to flooding";
            else if (firstHeadline.contains("drought")) detail = "amid drought conditions";
            else if (firstHeadline.contains("convoy")) detail = "after convoy attacks";
            else if (firstHeadline.contains("flee") || firstHeadline.contains("displacement")) detail = "driving population movement";
        }

        String summary = country + " – " + type.getDescription();
        if (!detail.isEmpty()) {
            summary += " " + detail;
        }

        return summary;
    }

    /**
     * Build today's intelligence summary (top 3-4 bullet points)
     */
    private List<String> buildTodaySummary(List<Situation> situations) {
        List<String> summary = new ArrayList<>();

        for (var situation : situations.stream().limit(4).collect(Collectors.toList())) {
            summary.add(situation.getSummary());
        }

        if (summary.isEmpty()) {
            summary.add("No critical situations detected in last 24 hours");
        }

        return summary;
    }

    /**
     * Enrich situations with trajectory (from risk scores) and related countries.
     */
    private void enrichSituations(List<Situation> situations) {
        // Get all risk scores for trajectory data
        var allScores = riskScoreService.getAllRiskScores();
        Map<String, com.crisismonitor.model.RiskScore> scoreMap = new HashMap<>();
        if (allScores != null) {
            for (var s : allScores) {
                if (s.getIso3() != null) scoreMap.put(s.getIso3(), s);
            }
        }

        // Group situations by region for related-countries detection
        // Using region (not type) gives more meaningful "also affected" results
        Map<String, List<String>> regionToCountries = new HashMap<>();
        for (Situation sit : situations) {
            String region = MonitoredCountries.getRegion(sit.getIso3());
            regionToCountries.computeIfAbsent(region, k -> new ArrayList<>())
                    .add(sit.getCountryName() != null ? sit.getCountryName() : sit.getIso3());
        }

        for (Situation sit : situations) {
            // Trajectory from risk score trend
            var riskScore = scoreMap.get(sit.getIso3());
            if (riskScore != null && riskScore.getTrend() != null) {
                switch (riskScore.getTrend()) {
                    case "rising" -> {
                        sit.setTrajectory("WORSENING");
                        sit.setTrajectoryReason("Risk score trending up" +
                                (riskScore.getScoreDelta() != null ? " (+" + riskScore.getScoreDelta() + " in 7d)" : ""));
                    }
                    case "falling" -> {
                        sit.setTrajectory("IMPROVING");
                        sit.setTrajectoryReason("Risk score declining" +
                                (riskScore.getScoreDelta() != null ? " (" + riskScore.getScoreDelta() + " in 7d)" : ""));
                    }
                    case "stable" -> {
                        sit.setTrajectory("STABLE");
                        sit.setTrajectoryReason("Risk score stable over 7 days");
                    }
                    default -> {
                        sit.setTrajectory("UNKNOWN");
                        sit.setTrajectoryReason("Insufficient trend data");
                    }
                }
            } else {
                sit.setTrajectory("UNKNOWN");
                sit.setTrajectoryReason("No risk score available");
            }

            // Related countries (same region, excluding self)
            String sitRegion = MonitoredCountries.getRegion(sit.getIso3());
            List<String> related = regionToCountries.getOrDefault(sitRegion, Collections.emptyList())
                    .stream()
                    .filter(name -> !name.equals(sit.getCountryName()) && !name.equals(sit.getIso3()))
                    .limit(5)
                    .collect(Collectors.toList());
            if (!related.isEmpty()) {
                sit.setRelatedCountries(related);
            }
        }
    }

    private int severityOrder(String severity) {
        switch (severity) {
            case "CRITICAL": return 4;
            case "HIGH": return 3;
            case "ELEVATED": return 2;
            case "WATCH": return 1;
            default: return 0;
        }
    }

    // ========================================
    // DTOs
    // ========================================

    @Data
    public static class SituationReport {
        private String status;
        private String timestamp;
        private String date;
        private List<String> todaySummary;
        private List<Situation> situations;
        private int totalSituations;
    }

    @Data
    public static class Situation {
        private String iso3;
        private String countryName;
        private String situationType;
        private String situationLabel;
        private String summary;
        private String severity;
        private int riskScore;
        private int articlesCount;
        private int reportsCount;
        private List<String> signals;
        private List<Evidence> evidence;
        // Enrichment fields
        private String trajectory;          // WORSENING, STABLE, IMPROVING, UNKNOWN
        private String trajectoryReason;    // Brief explanation
        private List<String> relatedCountries; // Other countries with same situation type
    }

    @Data
    public static class Evidence {
        private String source;
        private String title;
        private String url;
        private String publisher;

        public Evidence(String source, String title, String url, String publisher) {
            this.source = source;
            this.title = title;
            this.url = url;
            this.publisher = publisher;
        }
    }

    @Data
    public static class SituationType {
        private String label;
        private List<String> keywords;
        private String description;

        public SituationType(String label, List<String> keywords, String description) {
            this.label = label;
            this.keywords = keywords;
            this.description = description;
        }
    }
}
