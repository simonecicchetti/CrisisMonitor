package com.crisismonitor.service;

import com.crisismonitor.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Risk Score Service - Unified crisis scoring engine
 *
 * Three-layer architecture:
 * Layer 1 (Formula): Real-time data → deterministic scores
 *   - Conflict (35%) - GDELT + ACLED baselines + multi-signal
 *   - Food Security (35%) - WFP FCS/rCSI + Nowcast ML trajectory
 *   - Climate (15%) - precipitation anomaly + NDVI calibration
 *   - Economic (15%) - currency devaluation (capped at 60)
 *
 * Layer 2 (ML Nowcast): 3-model ensemble predicts 90-day food insecurity trajectory.
 *   Amplifies food score when worsening predicted (early warning).
 *
 * Layer 3 (AI Analyst): LLM + web search produces contextual scores with reasoning.
 *   Merged with formula using MAX logic: per-dimension max(Qwen, Formula).
 *   Qwen can raise scores (context) but never lower real-time signals.
 *   Verified conflicts/crises set minimum floors.
 *
 * Scoring: Weighted power mean (p=1.5). Humanitarian gate reduces pure climate/economic scores.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskScoreService {

    private final OpenMeteoService openMeteoService;
    private final CurrencyService currencyService;
    private final GDELTService gdeltService;
    private final FewsNetService fewsNetService;
    private final HungerMapService hungerMapService;
    private final TrendTrackingService trendTrackingService;
    private final ClimateService climateService;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private CacheWarmupService cacheWarmupService;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private QwenScoringService qwenScoringService;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private NowcastService nowcastService;

    // ISO2 to ISO3 mapping - all monitored countries
    private static final Map<String, String> ISO2_TO_ISO3 = Map.ofEntries(
            // Africa - East
            Map.entry("SD", "SDN"), Map.entry("SS", "SSD"), Map.entry("SO", "SOM"),
            Map.entry("ET", "ETH"), Map.entry("KE", "KEN"), Map.entry("UG", "UGA"),
            // Africa - Central
            Map.entry("CD", "COD"), Map.entry("CF", "CAF"), Map.entry("TD", "TCD"),
            Map.entry("CM", "CMR"), Map.entry("RW", "RWA"), Map.entry("BI", "BDI"),
            // Africa - West
            Map.entry("NG", "NGA"), Map.entry("ML", "MLI"), Map.entry("BF", "BFA"),
            Map.entry("NE", "NER"),
            // Africa - Other
            Map.entry("LY", "LBY"), Map.entry("MZ", "MOZ"), Map.entry("ZW", "ZWE"),
            // MENA
            Map.entry("YE", "YEM"), Map.entry("AF", "AFG"), Map.entry("IQ", "IRQ"),
            Map.entry("SY", "SYR"), Map.entry("LB", "LBN"), Map.entry("PS", "PSE"),
            Map.entry("IR", "IRN"), Map.entry("IL", "ISR"),
            // Asia
            Map.entry("PK", "PAK"), Map.entry("BD", "BGD"), Map.entry("IN", "IND"),
            Map.entry("MM", "MMR"),
            // Southeast Asia
            Map.entry("PH", "PHL"), Map.entry("ID", "IDN"), Map.entry("VN", "VNM"),
            // LAC
            Map.entry("HT", "HTI"), Map.entry("VE", "VEN"), Map.entry("CO", "COL"),
            Map.entry("GT", "GTM"), Map.entry("HN", "HND"), Map.entry("SV", "SLV"),
            Map.entry("NI", "NIC"), Map.entry("MX", "MEX"), Map.entry("PE", "PER"),
            Map.entry("EC", "ECU"), Map.entry("CU", "CUB"), Map.entry("PA", "PAN"),
            // Europe
            Map.entry("UA", "UKR")
    );

    private static final Map<String, String> COUNTRY_NAMES = Map.ofEntries(
            // Africa - East
            Map.entry("SD", "Sudan"), Map.entry("SS", "South Sudan"), Map.entry("SO", "Somalia"),
            Map.entry("ET", "Ethiopia"), Map.entry("KE", "Kenya"), Map.entry("UG", "Uganda"),
            // Africa - Central
            Map.entry("CD", "DR Congo"), Map.entry("CF", "CAR"), Map.entry("TD", "Chad"),
            Map.entry("CM", "Cameroon"), Map.entry("RW", "Rwanda"), Map.entry("BI", "Burundi"),
            // Africa - West
            Map.entry("NG", "Nigeria"), Map.entry("ML", "Mali"), Map.entry("BF", "Burkina Faso"),
            Map.entry("NE", "Niger"),
            // Africa - Other
            Map.entry("LY", "Libya"), Map.entry("MZ", "Mozambique"), Map.entry("ZW", "Zimbabwe"),
            // MENA
            Map.entry("YE", "Yemen"), Map.entry("AF", "Afghanistan"), Map.entry("IQ", "Iraq"),
            Map.entry("SY", "Syria"), Map.entry("LB", "Lebanon"), Map.entry("PS", "Palestine"),
            Map.entry("IR", "Iran"), Map.entry("IL", "Israel"),
            // Asia
            Map.entry("PK", "Pakistan"), Map.entry("BD", "Bangladesh"), Map.entry("IN", "India"),
            Map.entry("MM", "Myanmar"),
            // Southeast Asia
            Map.entry("PH", "Philippines"), Map.entry("ID", "Indonesia"), Map.entry("VN", "Vietnam"),
            // LAC
            Map.entry("HT", "Haiti"), Map.entry("VE", "Venezuela"), Map.entry("CO", "Colombia"),
            Map.entry("GT", "Guatemala"), Map.entry("HN", "Honduras"), Map.entry("SV", "El Salvador"),
            Map.entry("NI", "Nicaragua"), Map.entry("MX", "Mexico"), Map.entry("PE", "Peru"),
            Map.entry("EC", "Ecuador"), Map.entry("CU", "Cuba"), Map.entry("PA", "Panama"),
            // Europe
            Map.entry("UA", "Ukraine")
    );

    // Thresholds for "elevated" status
    private static final double CLIMATE_THRESHOLD = 30;    // Risk score >= 30
    private static final double CONFLICT_THRESHOLD = 1.0;  // z-score >= 1.0
    private static final double ECONOMIC_THRESHOLD = 30;   // Risk score >= 30 (or >5% devaluation)
    private static final double IPC_THRESHOLD = 3.0;       // IPC Phase >= 3 (Crisis)

    /**
     * Calculate risk score for a single country
     */
    @Cacheable(value = "riskScore", key = "#iso2")
    public RiskScore calculateRiskScore(String iso2) {
        log.info("Calculating risk score for {}", iso2);

        String iso3 = ISO2_TO_ISO3.get(iso2);
        String countryName = COUNTRY_NAMES.get(iso2);

        if (iso3 == null) {
            log.warn("Unknown country: {}", iso2);
            return null;
        }

        // Gather data from all sources
        int climateScore = 0;
        int conflictScore = 0;
        int economicScore = 0;
        int foodSecurityScore = 0;

        Double precipAnomaly = null;
        Double gdeltZScore = null;
        Double currencyChange = null;
        Double ipcPhase = null;

        double confidence = 0;
        int dataPoints = 0;

        // 1. Climate data (Open-Meteo precipitation + NDVI calibration)
        try {
            PrecipitationAnomaly precip = openMeteoService.getPrecipitationAnomaly(iso2);
            if (precip != null) {
                climateScore = precip.getRiskScore();
                precipAnomaly = precip.getAnomalyPercent();
                dataPoints++;

                // NDVI calibration: modulate climate score by actual vegetation impact
                // Precipitation deficit in arid zones (Sahara) where NDVI is normal = false positive
                // Uses memory fallback (populated by warmup) to avoid Redis cache issues
                try {
                    @SuppressWarnings("unchecked")
                    List<com.crisismonitor.model.ClimateData> ndviData = cacheWarmupService != null
                            ? (List<com.crisismonitor.model.ClimateData>) cacheWarmupService.getFallback("ndviClimateData")
                            : null;
                    if (ndviData == null || ndviData.isEmpty()) {
                        ndviData = climateService.getClimateAnomalies();
                    }
                    if (ndviData != null && !ndviData.isEmpty()) {
                        com.crisismonitor.model.ClimateData countryNdvi = ndviData.stream()
                                .filter(c -> iso3.equals(c.getIso3()))
                                .findFirst().orElse(null);
                        if (countryNdvi != null && countryNdvi.getNdviAnomaly() != null) {
                            String ndviAlert = countryNdvi.getAlertLevel();
                            if ("NORMAL".equals(ndviAlert)) {
                                // NDVI >= 0.9: vegetation healthy despite rain deficit → reduce score
                                int original = climateScore;
                                climateScore = (int)(climateScore * 0.4);
                                log.debug("NDVI calibration {}: NORMAL (ndvi={}) → climate {} → {}",
                                        iso3, String.format("%.3f", countryNdvi.getNdviAnomaly()), original, climateScore);
                            } else if ("MODERATE".equals(ndviAlert)) {
                                // NDVI 0.7-0.9: partial vegetation stress
                                int original = climateScore;
                                climateScore = (int)(climateScore * 0.7);
                                log.debug("NDVI calibration {}: MODERATE (ndvi={}) → climate {} → {}",
                                        iso3, String.format("%.3f", countryNdvi.getNdviAnomaly()), original, climateScore);
                            }
                            // SEVERE (NDVI < 0.7): keep full score — real crisis
                        }
                        // No NDVI data for country → keep precipitation score (conservative)
                    }
                } catch (Exception ndviEx) {
                    log.debug("NDVI calibration unavailable for {}: {}", iso3, ndviEx.getMessage());
                }
            }

            // Cap climate score: precipitation anomaly alone should not exceed 50.
            // Only NDVI SEVERE (confirmed vegetation crisis) allows higher scores.
            // This prevents false positives like Venezuela climate=70 from rain anomaly
            // when the actual crisis is economic, not climate.
            boolean ndviSevere = false;
            try {
                @SuppressWarnings("unchecked")
                List<com.crisismonitor.model.ClimateData> stressData = cacheWarmupService != null
                        ? (List<com.crisismonitor.model.ClimateData>) cacheWarmupService.getFallback("climateStress")
                        : null;
                if (stressData != null) {
                    ndviSevere = stressData.stream().anyMatch(c -> iso3.equals(c.getIso3()));
                }
            } catch (Exception e) { /* skip */ }

            if (!ndviSevere && climateScore > 50) {
                log.debug("Climate cap {}: {} → 50 (no NDVI SEVERE confirmation)", iso3, climateScore);
                climateScore = 50;
            }

            // Dampen climate score in active conflict zones:
            int conflictBaseline = CONFLICT_BASELINE.getOrDefault(iso3, 0);
            if (conflictBaseline >= 35 && climateScore > 0) {
                double dampFactor = conflictBaseline >= 60 ? 0.4 : 0.6;
                int original = climateScore;
                climateScore = (int)(climateScore * dampFactor);
                log.debug("Conflict dampening {}: baseline={} factor={} → climate {} → {}",
                        iso3, conflictBaseline, dampFactor, original, climateScore);
            }
        } catch (Exception e) {
            log.warn("Could not get climate data for {}: {}", iso2, e.getMessage());
        }

        // 2. Conflict data — multi-signal approach (GDELT + Situations + News + ReliefWeb)
        // Provides immediate conflict scoring even before GDELT warmup completes
        try {
            conflictScore = calculateMultiSignalConflictScore(iso3);
            if (conflictScore > 0) {
                // Derive a pseudo z-score for threshold checks (conflictScore/25 - 1)
                gdeltZScore = (conflictScore / 25.0) - 1.0;
                dataPoints++;
            }

            // Use bulk GDELT data (from memoryFallback) for volume floor.
            // This avoids per-country API calls which are rate-limited and often fail/timeout.
            // The per-country getConflictSpikeIndex has a 4h TTL and makes fresh API calls
            // when expired — during risk score calc this causes 47×45s = 35min of waits.
            if (cacheWarmupService != null) {
                MediaSpike spike = null;

                // First: try bulk data from memoryFallback (always available after preload/Phase 2)
                @SuppressWarnings("unchecked")
                List<MediaSpike> allSpikes = cacheWarmupService.getFallback("gdeltAllSpikes");
                if (allSpikes != null) {
                    spike = allSpikes.stream()
                            .filter(s -> iso3.equals(s.getIso3()))
                            .findFirst().orElse(null);
                }

                // Fallback: per-country cache (only if bulk not available and cache ready)
                if (spike == null && cacheWarmupService.isCacheReady("conflict")) {
                    try {
                        spike = gdeltService.getConflictSpikeIndex(iso3);
                    } catch (Exception ignored) {
                        // Per-country call can timeout/fail — don't let it break scoring
                    }
                }

                if (spike != null && spike.getZScore() != null) {
                    gdeltZScore = spike.getZScore();
                    // Cap z-score component at 60: spikes alone shouldn't dominate
                    // Volume floor (below) still lifts chronic conflicts (200+ articles → 75)
                    int gdeltScore = Math.min(60, Math.max(0, (int)(gdeltZScore * 25 + 25)));

                    // Volume floor for chronic conflicts
                    if (spike.getArticlesLast7Days() != null) {
                        int articles = spike.getArticlesLast7Days();
                        int volumeFloor = 0;
                        if (articles > 200) volumeFloor = 75;
                        else if (articles > 100) volumeFloor = 60;
                        else if (articles > 50) volumeFloor = 45;
                        else if (articles > 30) volumeFloor = 30;
                        gdeltScore = Math.max(gdeltScore, volumeFloor);
                    }

                    // Take the max of GDELT and multi-signal (GDELT enriches, never reduces)
                    conflictScore = Math.max(conflictScore, gdeltScore);
                    if (dataPoints == 0) dataPoints++; // count if not already counted
                }
            }
        } catch (Exception e) {
            log.warn("Could not get conflict data for {}: {}", iso3, e.getMessage());
        }

        // 3. Economic data (Currency + economic floor for unreliable exchange rate APIs)
        try {
            CurrencyData currency = currencyService.getCurrencyData(iso2);
            if (currency != null) {
                economicScore = currency.getRiskScore();
                currencyChange = currency.getChange30d();
                dataPoints++;
            }
        } catch (Exception e) {
            log.warn("Could not get currency data for {}: {}", iso2, e.getMessage());
        }

        // Economic floor for countries with known economic crises where exchange rate
        // APIs return unreliable data (official rates ≠ market rates for sanctioned economies).
        // Syria: SYP official ≠ black market. Zimbabwe: ZWL→ZiG currency reset.
        // Iran: official 42K IRR but real rate 600K+. These are KNOWN economic crises.
        economicScore = Math.max(economicScore, ECONOMIC_FLOOR.getOrDefault(iso3, 0));
        if (ECONOMIC_FLOOR.containsKey(iso3) && economicScore > 0 && dataPoints == 0) {
            dataPoints++;
        }

        // 4. Food Security — MULTI-SOURCE, MAX WINS
        // Three independent public sources, take the highest score.
        // Conservative: better to overestimate food insecurity than underestimate.
        // Zero hardcoded values — all from public APIs.
        //
        // Source A: WFP HungerMap FCS/rCSI (real-time, ~80 countries)
        // Source B: WFP HungerMap Severity Tier (real-time, ~255 countries)
        // Source C: World Bank Severe Food Insecurity % (annual, ~150 countries)

        int foodScoreA = 0; // WFP FCS/rCSI
        int foodScoreB = 0; // WFP Severity Tier
        int foodScoreC = 0; // World Bank SFI

        // Source A: WFP FCS/rCSI (real-time phone surveys)
        try {
            List<FoodSecurityMetrics> fcsMetrics = hungerMapService.getFoodSecurityMetrics();
            if (fcsMetrics != null) {
                FoodSecurityMetrics countryFcs = fcsMetrics.stream()
                        .filter(m -> iso3.equals(m.getIso3()))
                        .findFirst().orElse(null);
                if (countryFcs != null) {
                    Double fcs = countryFcs.getFcsPrevalence();
                    Double rcsi = countryFcs.getRcsiPrevalence();
                    if (fcs != null && rcsi != null) {
                        foodScoreA = Math.min(100, (int)((fcs + rcsi) / 2.0 * 100));
                    } else if (fcs != null) {
                        foodScoreA = Math.min(100, (int)(fcs * 100));
                    } else if (rcsi != null) {
                        foodScoreA = Math.min(100, (int)(rcsi * 100));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("WFP FCS/rCSI not available for {}: {}", iso3, e.getMessage());
        }

        // Source B: WFP Severity Tier (public API, 255 countries)
        try {
            Map<String, Integer> severityData = hungerMapService.getSeverityData();
            if (severityData != null) {
                Integer tier = severityData.get(iso3);
                if (tier != null) {
                    foodScoreB = tier == 1 ? 95 : tier == 2 ? 75 : tier == 3 ? 55 : 20;
                }
            }
        } catch (Exception e) {
            log.debug("Severity tier not available for {}: {}", iso3, e.getMessage());
        }

        // Source C: World Bank Severe Food Insecurity (annual, ~150 countries)
        try {
            @SuppressWarnings("unchecked")
            Map<String, Double> wbSfi = cacheWarmupService.getFallback("worldBankSFI");
            if (wbSfi != null) {
                Double sfiPercent = wbSfi.get(iso3);
                if (sfiPercent != null) {
                    // Direct mapping: 63% SFI → score 63
                    foodScoreC = Math.min(100, (int) Math.round(sfiPercent));
                }
            }
        } catch (Exception e) {
            log.debug("World Bank SFI not available for {}: {}", iso3, e.getMessage());
        }

        // MAX WINS — take highest score from all sources
        foodSecurityScore = Math.max(foodScoreA, Math.max(foodScoreB, foodScoreC));
        if (foodSecurityScore > 0) dataPoints++;

        // Derive IPC phase from food score for compatibility
        if (foodSecurityScore > 0) {
            ipcPhase = Math.min(5.0, foodSecurityScore / 20.0);
        }

        String foodSource = foodSecurityScore == foodScoreA ? "WFP_FCS" :
                           foodSecurityScore == foodScoreB ? "WFP_Severity" : "WorldBank_SFI";
        // (Nowcast amplifier applied as final step in getAllRiskScores, after Qwen overlay)
        log.debug("Food score for {}: A(FCS)={}, B(Tier)={}, C(WB)={} → max={} (source={})",
                iso3, foodScoreA, foodScoreB, foodScoreC, foodSecurityScore, foodSource);

        // 5. Economic stress amplifier: severe economic crisis cascades into food insecurity
        // Only for verified IPC data (foodSecurityScore >= 60 = IPC Phase 3+)
        // Proxy data (FCS, capped at 50) should not be amplified further.
        if (economicScore >= 70 && foodSecurityScore >= 60) {
            int amplifier = (economicScore - 70) / 5; // +6 max at economicScore=100
            foodSecurityScore = Math.min(100, foodSecurityScore + amplifier);
            log.debug("Economic stress amplifier for {}: +{} → foodSecurityScore={}", iso3, amplifier, foodSecurityScore);
        }

        // Calculate confidence based on data availability
        confidence = dataPoints / 4.0;

        // Determine which indicators are elevated
        boolean climateElevated = climateScore >= CLIMATE_THRESHOLD;
        boolean conflictElevated = conflictScore >= 30;
        boolean economicElevated = economicScore >= ECONOMIC_THRESHOLD;
        boolean foodElevated = foodSecurityScore >= 60;  // IPC Phase 3+
        int elevatedCount = (climateElevated ? 1 : 0) + (conflictElevated ? 1 : 0)
                + (economicElevated ? 1 : 0) + (foodElevated ? 1 : 0);

        // Calculate weighted power mean (p=1.5)
        // Power mean emphasizes extreme component values without the zero-breakage
        // problem of geometric mean. p=1 is arithmetic, p=2 is quadratic. p=1.5 is
        // the sweet spot: countries with one extreme signal score higher than arithmetic
        // mean, matching real-world severity (INFORM Risk methodology research).
        // Weights: Conflict 35%, Food Security 35%, Climate 15%, Economic 15%
        // Cap economic contribution at 60 to prevent pure currency shocks from inflating scores.
        // Exception: countries at war (conflict baseline >= 60) where economic collapse compounds
        // the humanitarian crisis — allow up to 80 to reflect the compounding effect.
        int economicCap = CONFLICT_BASELINE.getOrDefault(iso3, 0) >= 60 ? 80 : 60;
        int economicCapped = Math.min(economicScore, economicCap);
        double p = 1.5;
        // Weights: Conflict and Food are THE primary drivers of humanitarian crises.
        // Climate is a slow-onset amplifier — important long-term but must not drag down
        // countries in active crisis that happen to have OK NDVI (e.g. Ethiopia climate=4).
        // At 25% climate weight, a country at war with IPC Phase 4 could score WARNING
        // simply because satellite vegetation looks green. That's indefensible.
        double powerSum = 0.35 * Math.pow(Math.max(1, conflictScore), p)
                + 0.35 * Math.pow(Math.max(1, foodSecurityScore), p)
                + 0.15 * Math.pow(Math.max(1, climateScore), p)
                + 0.15 * Math.pow(Math.max(1, economicCapped), p);
        int overallScore = (int) Math.pow(powerSum, 1.0 / p);

        // === WAR OVERRIDE ===
        // WAR IS WAR: if a country has conflict baseline >= 60 (active war),
        // the overall score MUST reflect that reality. A country at war cannot
        // score below WARNING regardless of what other sensors show.
        // This prevents absurd situations like "Iran WATCH" during active bombing.
        int conflictBase = CONFLICT_BASELINE.getOrDefault(iso3, 0);
        if (conflictBase >= 60) {
            // Active war floor: score cannot be lower than conflict-driven minimum
            // baseline 80 → floor 60 (CRITICAL), 75 → 56, 70 → 52, 65 → 48 (ALERT), 60 → 45
            int warFloor = (int)(conflictBase * 0.75);
            if (overallScore < warFloor) {
                log.debug("War override {}: score {} → floor {} (conflictBase={})",
                        iso3, overallScore, warFloor, conflictBase);
                overallScore = warFloor;
            }
        }

        // Humanitarian gate: if neither conflict nor food security is significantly
        // elevated (>= 50), reduce score by 15%. Pure climate/economic anomalies
        // (e.g., precipitation outlier + currency depreciation) should not drive
        // a country to WARNING without humanitarian corroboration.
        // SKIP if country has verified conflict (CONFLICT_BASELINE > 0) — these are
        // manually validated conflict zones, not false positives from sensor noise.
        // Previously used conflictBase < 60, which wrongly penalized countries like
        // Libya (base=40), Pakistan (base=30), Iraq (base=35) with real conflicts.
        if (conflictBase == 0 && conflictScore < 50 && foodSecurityScore < 50 && overallScore > 30) {
            overallScore = (int)(overallScore * 0.85);
        }

        // Determine risk level — thresholds calibrated for power mean score distribution
        String riskLevel;
        if (overallScore >= 60) riskLevel = "CRITICAL";
        else if (overallScore >= 48) riskLevel = "ALERT";
        else if (overallScore >= 38) riskLevel = "WARNING";
        else if (overallScore >= 22) riskLevel = "WATCH";
        else riskLevel = "STABLE";

        // Determine primary drivers
        List<String> drivers = new ArrayList<>();
        Map<String, Integer> driverScores = new LinkedHashMap<>();
        driverScores.put("Food Security", foodSecurityScore);
        driverScores.put("Climate", climateScore);
        driverScores.put("Conflict", conflictScore);
        driverScores.put("Economic", economicScore);

        // WAR IS WAR: if conflict baseline >= 60, Conflict is ALWAYS the first driver.
        // A country at war shows "Conflict" as primary — not "Climate" or "Food Security".
        // Other drivers follow by score as secondary/tertiary context.
        boolean atWar = conflictBase >= 60;

        if (atWar) {
            drivers.add("Conflict");
            // Add remaining drivers sorted by score
            driverScores.entrySet().stream()
                    .filter(e -> !"Conflict".equals(e.getKey()))
                    .filter(e -> e.getValue() >= 30)
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .map(Map.Entry::getKey)
                    .limit(2)
                    .forEach(drivers::add);
        } else {
            // Normal driver ranking: sort by score with humanitarian priority bands.
            // Scores within 5 points are treated as equivalent, then humanitarian
            // hierarchy breaks the tie: Food > Conflict > Economic > Climate.
            // This prevents misleading labels like "Economic" for South Sudan when
            // Economic=85 vs Food=83 — the 2-point gap is noise, but the label
            // matters to analysts who expect SSD to show "Food Security".
            Map<String, Integer> driverPriority = Map.of(
                    "Food Security", 0, "Conflict", 1, "Economic", 2, "Climate", 3);
            drivers = driverScores.entrySet().stream()
                    .filter(e -> "Conflict".equals(e.getKey()) ? e.getValue() >= 20 : e.getValue() >= 30)
                    .sorted((a, b) -> {
                        int diff = b.getValue() - a.getValue();
                        // Only use score ordering if difference > 5 points
                        if (Math.abs(diff) > 5) return Integer.compare(b.getValue(), a.getValue());
                        // Within 5-point band: use humanitarian priority
                        return Integer.compare(
                                driverPriority.getOrDefault(a.getKey(), 9),
                                driverPriority.getOrDefault(b.getKey(), 9));
                    })
                    .map(Map.Entry::getKey)
                    .limit(3)
                    .collect(Collectors.toList());
        }

        if (drivers.isEmpty()) {
            drivers.add("No significant drivers");
        }

        // Determine time horizon
        String horizon;
        String horizonReason;
        if (conflictElevated && climateElevated) {
            horizon = "30 days";
            horizonReason = "Conflict + climate stress accelerates crisis";
        } else if (conflictElevated) {
            horizon = "30-60 days";
            horizonReason = "Conflict-driven crises develop rapidly";
        } else if (climateElevated) {
            horizon = "60-90 days";
            horizonReason = "Climate impacts develop over agricultural cycle";
        } else if (economicElevated) {
            horizon = "60-90 days";
            horizonReason = "Economic shocks take time to impact food security";
        } else {
            horizon = "90+ days";
            horizonReason = "No immediate crisis drivers detected";
        }

        // Confidence note
        String confidenceNote;
        if (confidence >= 0.75) confidenceNote = "High - all data sources available";
        else if (confidence >= 0.5) confidenceNote = "Medium - some data sources unavailable";
        else confidenceNote = "Low - limited data availability";

        return RiskScore.builder()
                .iso3(iso3)
                .iso2(iso2)
                .countryName(countryName)
                .score(overallScore)
                .riskLevel(riskLevel)
                .climateScore(climateScore)
                .conflictScore(conflictScore)
                .economicScore(economicScore)
                .foodSecurityScore(foodSecurityScore)
                .climateElevated(climateElevated)
                .conflictElevated(conflictElevated)
                .economicElevated(economicElevated)
                .elevatedCount(elevatedCount)
                .drivers(drivers)
                .horizon(horizon)
                .horizonReason(horizonReason)
                .confidence(Math.round(confidence * 100) / 100.0)
                .confidenceNote(confidenceNote)
                .precipitationAnomaly(precipAnomaly)
                .currencyChange30d(currencyChange)
                .gdeltZScore(gdeltZScore)
                .ipcPhase(ipcPhase)
                .nowcastAmplifier(null)  // Set by applyNowcastAmplifier() after Qwen overlay
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Get risk scores for all monitored countries
     */
    @Cacheable(value = "allRiskScores")
    public List<RiskScore> getAllRiskScores() {
        log.info("Calculating risk scores for all monitored countries");

        List<RiskScore> scores = new ArrayList<>();

        for (String iso2 : ISO2_TO_ISO3.keySet()) {
            try {
                RiskScore score = calculateRiskScore(iso2);
                if (score != null) {
                    // Enrich with trend data
                    trendTrackingService.enrichWithTrend(score);
                    scores.add(score);
                }
                // Small delay to avoid overwhelming APIs
                Thread.sleep(100);
            } catch (Exception e) {
                log.warn("Error calculating risk score for {}: {}", iso2, e.getMessage());
            }
        }

        // Layer 3: Overlay Qwen AI scores (replaces formula scores where available)
        overlayQwenScores(scores);

        // Layer 2: Apply Nowcast ML trajectory amplifier (AFTER Qwen overlay)
        // This is the final step — amplifies food score regardless of source (formula or Qwen)
        applyNowcastAmplifier(scores);

        // Sort by score descending (highest risk first)
        scores.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));

        log.info("Calculated risk scores for {} countries", scores.size());
        return scores;
    }

    /**
     * Overlay Qwen AI scores onto formula-based scores.
     * Qwen scores are pre-generated weekly and stored in Firestore.
     * If Qwen score exists and is fresh (< 14 days), it replaces the formula score.
     * Formula scores are kept as fallback.
     */
    private void overlayQwenScores(List<RiskScore> scores) {
        int overlaid = 0;

        for (RiskScore rs : scores) {
            try {
                QwenScoringService.CountryScore qwen = qwenScoringService.getScore(rs.getIso3());
                if (qwen == null) {
                    rs.setScoreSource("formula");
                    continue;
                }

                // Save formula scores before merge (for logging and reason annotations)
                int formulaFood = rs.getFoodSecurityScore();
                int formulaConflict = rs.getConflictScore();
                int formulaClimate = rs.getClimateScore();
                int formulaEconomic = rs.getEconomicScore();

                // Merge Qwen AI assessment with formula scores using MAX logic:
                // For each dimension, take the HIGHER of (Qwen, Formula).
                // - If Qwen is higher → Qwen sees context formula misses (e.g. forecasted drought)
                // - If Formula is higher → something changed since Qwen last ran (e.g. overnight attack)
                // This prevents stale Qwen scores from masking real-time deterioration.

                // Conflict: max(qwen, formula, verified_baseline)
                int conflictFloor = CONFLICT_BASELINE.getOrDefault(rs.getIso3(), 0);
                int finalConflict = Math.max(Math.max(qwen.getConflictScore(), rs.getConflictScore()), conflictFloor);
                String conflictReason = qwen.getConflictReason();
                if (finalConflict == conflictFloor && conflictFloor > qwen.getConflictScore() && conflictFloor >= 50) {
                    // Verified baseline is the highest — override both Qwen and formula
                    conflictReason = "Active armed conflict (verified baseline); " + conflictReason;
                } else if (formulaConflict > qwen.getConflictScore() && finalConflict == formulaConflict) {
                    // Formula real-time data is the highest
                    conflictReason = "Real-time data elevated; " + conflictReason;
                }

                // Food, Climate, Economic: max(qwen, formula)
                int foodScore = Math.max(qwen.getFoodScore(), rs.getFoodSecurityScore());
                int climateScore = Math.max(qwen.getClimateScore(), rs.getClimateScore());
                int economicScore = Math.max(qwen.getEconomicScore(), rs.getEconomicScore());

                // Enforce verified crisis floors on top
                Map<String, Integer> crisisFloors = QwenScoringService.getCrisisFloors(rs.getIso3());
                if (crisisFloors != null) {
                    foodScore = Math.max(foodScore, crisisFloors.getOrDefault("food", 0));
                    climateScore = Math.max(climateScore, crisisFloors.getOrDefault("climate", 0));
                    economicScore = Math.max(economicScore, crisisFloors.getOrDefault("economic", 0));
                }

                // Log when formula overrides Qwen (something new happened)
                if (formulaFood > qwen.getFoodScore())
                    log.info("  {} food: formula {} > Qwen {} (real-time override)", rs.getIso3(), formulaFood, qwen.getFoodScore());
                if (formulaConflict > qwen.getConflictScore())
                    log.info("  {} conflict: formula {} > Qwen {} (real-time override)", rs.getIso3(), formulaConflict, qwen.getConflictScore());
                if (formulaEconomic > qwen.getEconomicScore())
                    log.info("  {} economic: formula {} > Qwen {} (real-time override)", rs.getIso3(), formulaEconomic, qwen.getEconomicScore());

                rs.setFoodSecurityScore(foodScore);
                rs.setConflictScore(finalConflict);
                rs.setClimateScore(climateScore);
                rs.setEconomicScore(economicScore);

                // Recalculate overall with corrected conflict
                double p = 1.5;
                double wConflict = 0.35, wFood = 0.35, wClimate = 0.15, wEcon = 0.15;
                if (finalConflict <= 5) {
                    wFood += wConflict * 0.5;
                    wEcon += wConflict * 0.5;
                    wConflict = 0;
                }
                // Apply same economic cap as formula path
                int conflictFloorVal = CONFLICT_BASELINE.getOrDefault(rs.getIso3(), 0);
                int econCap = conflictFloorVal >= 60 ? 80 : 60;
                int econCapped = Math.min(economicScore, econCap);
                double powerSum = wConflict * Math.pow(Math.max(1, finalConflict), p)
                                + wFood * Math.pow(Math.max(1, foodScore), p)
                                + wClimate * Math.pow(Math.max(1, climateScore), p)
                                + wEcon * Math.pow(Math.max(1, econCapped), p);
                int recalcOverall = (int) Math.pow(powerSum, 1.0 / p);

                // War override (same as formula path)
                if (conflictFloorVal >= 60) {
                    int warFloor = (int)(conflictFloorVal * 0.75);
                    if (recalcOverall < warFloor) {
                        log.debug("Qwen path war override {}: {} → {}", rs.getIso3(), recalcOverall, warFloor);
                        recalcOverall = warFloor;
                    }
                }

                // Humanitarian gate (same as formula path)
                if (conflictFloorVal == 0 && finalConflict < 50 && foodScore < 50 && recalcOverall > 30) {
                    recalcOverall = (int)(recalcOverall * 0.85);
                }

                rs.setScore(recalcOverall);

                // Risk level from recalculated overall
                rs.setRiskLevel(recalcOverall >= 60 ? "CRITICAL" : recalcOverall >= 48 ? "ALERT" :
                               recalcOverall >= 38 ? "WARNING" : recalcOverall >= 22 ? "WATCH" : "STABLE");

                // Drivers from corrected scores
                // WAR IS WAR: if conflict floor >= 50, Conflict MUST be first driver
                List<String> drivers = new java.util.ArrayList<>();
                if (conflictFloor >= 50) {
                    drivers.add("Conflict");
                }
                Map<String, Integer> driverScores = Map.of(
                    "Food Security", foodScore, "Conflict", finalConflict,
                    "Climate", climateScore, "Economic", economicScore);
                driverScores.entrySet().stream()
                    .filter(e -> e.getValue() >= 30)
                    .filter(e -> !drivers.contains(e.getKey())) // skip if already added
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(conflictFloor >= 50 ? 2 : 3)
                    .forEach(e -> drivers.add(e.getKey()));
                rs.setDrivers(drivers);

                // Set AI reasons — note when formula overrode Qwen
                String foodReason = qwen.getFoodReason();
                if (formulaFood > qwen.getFoodScore())
                    foodReason = "Real-time data elevated; " + foodReason;
                String econReason = qwen.getEconomicReason();
                if (formulaEconomic > qwen.getEconomicScore())
                    econReason = "Real-time data elevated; " + econReason;
                String climReason = qwen.getClimateReason();
                if (formulaClimate > qwen.getClimateScore())
                    climReason = "Real-time data elevated; " + climReason;

                rs.setFoodReason(foodReason);
                rs.setConflictReason(conflictReason);
                rs.setClimateReason(climReason);
                rs.setEconomicReason(econReason);
                rs.setSummary(qwen.getSummary());
                rs.setScoreSource("qwen");
                rs.setQwenGeneratedAt(qwen.getGeneratedAt());

                // Recalculate elevated flags (use finalConflict, not Qwen's original)
                boolean climElevated = climateScore >= 30;
                boolean confElevated = finalConflict >= 30;
                boolean econElevated = economicScore >= 30;
                boolean foodElevated = foodScore >= 60;
                rs.setClimateElevated(climElevated);
                rs.setConflictElevated(confElevated);
                rs.setEconomicElevated(econElevated);
                rs.setElevatedCount((climElevated ? 1 : 0) + (confElevated ? 1 : 0)
                    + (econElevated ? 1 : 0) + (foodElevated ? 1 : 0));

                overlaid++;
            } catch (Exception e) {
                rs.setScoreSource("formula");
                log.debug("Qwen overlay failed for {}: {}", rs.getIso3(), e.getMessage());
            }
        }
        log.info("Qwen overlay: {}/{} countries using AI scores", overlaid, scores.size());
    }

    /**
     * Get high-risk countries (score >= 71)
     */
    public List<RiskScore> getHighRiskCountries() {
        return getAllRiskScores().stream()
                .filter(RiskScore::isHighRisk)
                .toList();
    }

    /**
     * Get countries with confirmed warnings (2-of-3 rule)
     */
    public List<RiskScore> getConfirmedWarnings() {
        return getAllRiskScores().stream()
                .filter(RiskScore::isConfirmed)
                .filter(s -> s.getScore() >= 51)
                .toList();
    }

    /**
     * Layer 2: Apply Nowcast ML trajectory amplifier to food scores.
     * Runs AFTER Qwen overlay so it applies to ALL countries regardless of score source.
     * If ML predicts worsening >3pp in 90 days, amplifies food score proportionally.
     * Only amplifies, never reduces (conservative early warning).
     */
    private void applyNowcastAmplifier(List<RiskScore> scores) {
        try {
            var nowcasts = nowcastService.getNowcastAll();
            if (nowcasts == null) return;

            int amplified = 0;
            for (RiskScore rs : scores) {
                NowcastResult nowcast = nowcasts.stream()
                        .filter(n -> rs.getIso3().equals(n.getIso3()))
                        .findFirst().orElse(null);
                if (nowcast == null || nowcast.getPredictedChange90d() == null) continue;

                double predicted = nowcast.getPredictedChange90d();
                rs.setNowcastPrediction(predicted);

                // Divergence detection: ML says "stable/improving" but other signals say otherwise.
                // When food score (from AI/formula) is much higher than proxy-based reality,
                // or climate/conflict are elevated, the ML forecast is blind to the risk.
                double proxyLevel = nowcast.getCurrentProxy() != null ? nowcast.getCurrentProxy() : 0;
                int proxyBasedScore = (int) Math.min(100, proxyLevel);
                int foodGap = rs.getFoodSecurityScore() - proxyBasedScore; // AI sees more than proxy
                boolean climateRisk = rs.getClimateScore() >= 50;
                boolean conflictRisk = rs.getConflictScore() >= 50;

                if (predicted < 2.0 && (foodGap > 20 || climateRisk || conflictRisk)) {
                    // ML says stable/slight change but external signals say otherwise
                    StringBuilder caveat = new StringBuilder("Indicators stable but ");
                    List<String> risks = new ArrayList<>();
                    if (climateRisk) risks.add("severe climate stress detected");
                    if (conflictRisk) risks.add("elevated conflict");
                    if (foodGap > 20) risks.add("AI assessment indicates higher food insecurity than tracked metrics capture");
                    caveat.append(String.join("; ", risks));
                    rs.setNowcastCaveat(caveat.toString());
                    log.info("Nowcast divergence {}: ML={}pp but foodGap={}, climate={}, conflict={}",
                            rs.getIso3(), String.format("%+.1f", predicted), foodGap, rs.getClimateScore(), rs.getConflictScore());
                }

                // Amplify when worsening predicted above noise floor (>1.5pp, ~1.5x MAE=1.05)
                if (predicted > 1.5) {
                    int amp = (int) Math.min(15, predicted * 1.0);
                    int oldFood = rs.getFoodSecurityScore();
                    int newFood = Math.min(100, oldFood + amp);
                    rs.setFoodSecurityScore(newFood);
                    rs.setNowcastAmplifier(amp);

                    // Recalculate overall score with amplified food
                    // Use same weight redistribution and economic cap logic as other paths
                    double p = 1.5;
                    int conflict = rs.getConflictScore();
                    double wC = 0.35, wF = 0.35, wCl = 0.15, wE = 0.15;
                    if (conflict <= 5) {
                        wF += wC * 0.5;
                        wE += wC * 0.5;
                        wC = 0;
                    }
                    int conflictBaseVal = CONFLICT_BASELINE.getOrDefault(rs.getIso3(), 0);
                    int eCap = conflictBaseVal >= 60 ? 80 : 60;
                    int eCapped = Math.min(rs.getEconomicScore(), eCap);
                    double powerSum = wC * Math.pow(Math.max(1, conflict), p)
                                    + wF * Math.pow(Math.max(1, newFood), p)
                                    + wCl * Math.pow(Math.max(1, rs.getClimateScore()), p)
                                    + wE * Math.pow(Math.max(1, eCapped), p);
                    int newOverall = (int) Math.pow(powerSum, 1.0 / p);
                    rs.setScore(Math.max(rs.getScore(), newOverall)); // Only increase, never decrease
                    rs.setRiskLevel(rs.getScore() >= 60 ? "CRITICAL" : rs.getScore() >= 48 ? "ALERT" :
                                   rs.getScore() >= 38 ? "WARNING" : rs.getScore() >= 22 ? "WATCH" : "STABLE");

                    amplified++;
                    log.info("Nowcast amplifier {}: predicted={}, food {}→{}, overall→{}",
                            rs.getIso3(), String.format("%+.1f", predicted), oldFood, newFood, rs.getScore());
                }
            }
            if (amplified > 0) {
                log.info("Nowcast amplifier: {}/{} countries had food score boosted", amplified, scores.size());
            }
        } catch (Exception e) {
            log.debug("Nowcast amplifier skipped: {}", e.getMessage());
        }
    }

    /**
     * Get summary statistics
     */
    public Map<String, Object> getRiskSummary() {
        List<RiskScore> scores = getAllRiskScores();

        Map<String, Long> byLevel = scores.stream()
                .collect(Collectors.groupingBy(RiskScore::getRiskLevel, Collectors.counting()));

        return Map.of(
                "total", scores.size(),
                "critical", byLevel.getOrDefault("CRITICAL", 0L),
                "alert", byLevel.getOrDefault("ALERT", 0L),
                "warning", byLevel.getOrDefault("WARNING", 0L),
                "watch", byLevel.getOrDefault("WATCH", 0L),
                "stable", byLevel.getOrDefault("STABLE", 0L),
                "confirmedWarnings", scores.stream().filter(RiskScore::isConfirmed).count()
        );
    }

    // ========================================
    // Multi-Signal Conflict Scoring
    // ========================================

    /**
     * Calculate conflict score from multiple cached sources — available immediately,
     * doesn't depend on GDELT warmup completing.
     *
     * Signals:
     *   1. GDELT cached spikes (if available)     → max 55 pts
     *   2. Situation Detection (VIOLENCE_ESCALATION) → max 25 pts
     *   3. News Briefing (CONFLICT topic headlines)  → max 15 pts
     *   4. Displacement surge (proxy for violence)   → max 10 pts
     *
     * @return conflict score 0-100
     */
    /**
     * Conflict baseline for countries with documented active armed conflicts.
     * Sources: ACLED, Uppsala UCDP, ICG CrisisWatch.
     *
     * IMPORTANT — WAR IS WAR:
     * These baselines represent the MINIMUM conflict score for each country.
     * When a country is at war, conflict MUST be the dominant signal.
     * Climate, economic, or food anomalies should never overshadow active armed conflict.
     * GDELT and other live signals add ON TOP of this baseline.
     *
     * Baseline tiers:
     *   75+ = Active war with international involvement (siege, bombing, invasion)
     *   60-74 = Active war (civil war, multi-front, sustained combat operations)
     *   45-59 = High-intensity conflict (insurgency, armed groups controlling territory)
     *   30-44 = Active insurgency / significant armed violence
     *   20-29 = Low-intensity conflict / post-conflict instability
     *
     * Last reviewed: March 16, 2026 — Full recalibration: IRC Watchlist + Iran war + Sahel siege + Hormuz
     */
    /**
     * Conflict floor for countries with VERIFIED ACTIVE WARS only.
     *
     * This exists because GDELT (media volume) systematically underestimates
     * conflicts in low-media-attention countries (CAR, Sahel, Myanmar).
     * Without this floor, a country at war shows conflict=5 on a quiet news day.
     *
     * ONLY includes countries with:
     * - Active armed conflict confirmed by multiple sources (IRC Watchlist, UCDP, ICG)
     * - Regular combat casualties
     * - Armed groups controlling territory
     *
     * Countries with political instability, state repression, or post-conflict
     * tensions are NOT included — GDELT handles those.
     *
     * Below 50: removed. GDELT decides. These are insurgencies where media coverage
     * correlates well enough with intensity.
     *
     * >>> CHANGE POINT: replace with ACLED data when budget allows
     * >>> CHANGE POINT: or calculate from GDELT BigQuery 180-day rolling average
     */
    private static final Map<String, Integer> CONFLICT_BASELINE = Map.ofEntries(
            // === ACTIVE WARS — international military operations ===
            Map.entry("PSE", 80),  // Gaza war: siege + ground operations
            Map.entry("IRN", 78),  // US/Israel war (Feb 2026): Hormuz blockade, ongoing strikes
            Map.entry("UKR", 75),  // Russia-Ukraine: full-scale invasion

            // === ACTIVE WARS — civil wars, multi-front ===
            Map.entry("SDN", 70),  // Civil war (SAF vs RSF), 150K+ dead
            Map.entry("ISR", 70),  // Multi-front: Gaza + Iran
            Map.entry("LBN", 65),  // Post-2024 war, ceasefire fragile
            Map.entry("MMR", 60),  // Nationwide civil war, junta controls 21% territory
            Map.entry("SYR", 60),  // Multi-front civil war
            Map.entry("YEM", 60),  // Houthi + US strikes + Hormuz

            // === HIGH-INTENSITY — verified armed groups controlling territory ===
            Map.entry("BFA", 55),  // JNIM/IS siege 40+ towns, IRC #6
            Map.entry("ETH", 55),  // Amhara insurgency (Fano), IRC #4
            Map.entry("COD", 55),  // M23 + ADF, IRC #8
            Map.entry("HTI", 53),  // Gangs control 80-90% Port-au-Prince, IRC #5
            Map.entry("SOM", 50),  // Al-Shabaab insurgency
            Map.entry("SSD", 50),  // Inter-communal violence
            Map.entry("MLI", 50)   // JNIM advancing on Bamako, IRC #7
            // Everything below 50 removed — GDELT handles these:
            // NGA, LBY, CAF, AFG, IRQ, PAK, TCD, COL, MOZ, NER, CMR, VEN
    );

    /**
     * Economic floor for countries with KNOWN economic crises where exchange rate
     * APIs return unreliable data. Official rates ≠ real rates for sanctioned or
     * collapsed economies. These are minimum economic scores.
     *
     * Why this is needed:
     * - Syria: API returns official SYP rate (~117) vs baseline 13000 → shows -99% "appreciation"
     * - Zimbabwe: ZWL→ZiG currency reset makes old baseline meaningless
     * - Iran: official IRR 42K but black market 600K+ → API captures devaluation but may understate
     * - Cuba: dual currency system, official rate is fiction
     */
    private static final Map<String, Integer> ECONOMIC_FLOOR = Map.ofEntries(
            Map.entry("SYR", 70),  // Economic collapse: 90%+ poverty, infrastructure destroyed
            Map.entry("IRN", 65),  // War economy 2026: rial 1.75M/$, Hormuz blocked, sanctions + strikes
            Map.entry("ZWE", 55),  // Currency reset (ZWL→ZiG), hyperinflation legacy
            Map.entry("CUB", 55),  // 2026 US oil blockade, GDP -7.2%, dual currency collapse, blackouts
            Map.entry("SDN", 40),  // War-driven economic collapse, no functioning banking, trade routes cut
            Map.entry("YEM", 40),  // War economy, divided central bank
            Map.entry("LBN", 40),  // Banking collapse (2019-present), 98% currency loss
            Map.entry("PSE", 35),  // Gaza siege: economy non-functional
            Map.entry("MMR", 30),  // Post-coup economic collapse
            Map.entry("AFG", 30),  // Taliban: banking system frozen, 97% near poverty
            Map.entry("NGA", 45)   // 40.9% food inflation, naira collapse, fuel subsidy removal
    );

    /**
     * Food security floor for countries with KNOWN food crises not captured by
     * FEWS NET or WFP HungerMap. These are sensor gaps — millions of people are
     * food insecure but our data sources don't cover these countries.
     *
     * Sources: UNHCR, WFP situation reports, academic estimates.
     * Venezuela: 7.7M refugees (2nd globally), widespread food shortages
     * Cuba: severe food crisis, rationing, blackouts disrupting food supply
     * Iraq: post-conflict food access issues in liberated areas
     */
    private static final Map<String, Integer> FOOD_FLOOR = Map.ofEntries(
            Map.entry("HTI", 55),  // 5.9M food insecure, 600K in famine conditions — IRC #5
            Map.entry("VEN", 50),  // 7.7M refugees, widespread malnutrition, HRP country
            Map.entry("ETH", 65),  // 20M+ food insecure, IPC underestimates scale — IRC #4
            Map.entry("CUB", 45),  // 2026 severe food crisis, rationing, blackouts, emigration
            Map.entry("IRN", 40),  // Sanctions-driven food inflation, rial collapse → import costs
            Map.entry("NGA", 70),  // 35M at risk of hunger (record), WFP defunded, northeast Boko Haram
            Map.entry("SDN", 65),  // War-driven famine, 25M+ food insecure, IPC Phase 5 — IRC #1
            Map.entry("BFA", 70),  // 2.1M IDPs, 40+ towns under JNIM siege, food access cut — IRC #6
            Map.entry("MLI", 60),  // JNIM/ISGS control, 1.3M food insecure, IDP crisis — IRC #7
            Map.entry("TCD", 50),  // 1M+ Sudanese refugees, host community food insecure, GHO 2026 country
            Map.entry("IRQ", 30),  // Post-conflict food access issues
            Map.entry("LBY", 30)   // Disrupted food supply chains, militia control
    );

    @SuppressWarnings("unchecked")
    private int calculateMultiSignalConflictScore(String iso3) {
        if (cacheWarmupService == null) return CONFLICT_BASELINE.getOrDefault(iso3, 0);

        // Start from baseline — live signals can only raise the score
        int score = CONFLICT_BASELINE.getOrDefault(iso3, 0);

        // Signal 1: GDELT cached spikes (from memory fallback — available even before cache "ready")
        try {
            List<MediaSpike> cachedSpikes = (List<MediaSpike>) cacheWarmupService.getFallback("gdeltAllSpikes");
            if (cachedSpikes != null) {
                MediaSpike spike = cachedSpikes.stream()
                        .filter(s -> iso3.equals(s.getIso3()))
                        .findFirst().orElse(null);
                if (spike != null && spike.getZScore() != null) {
                    double z = spike.getZScore();
                    if (z > 3.0) score += 55;
                    else if (z > 2.0) score += 40;
                    else if (z > 1.0) score += 25;
                    else if (z > 0.5) score += 15;

                    // Volume floor
                    if (spike.getArticlesLast7Days() != null) {
                        int articles = spike.getArticlesLast7Days();
                        if (articles > 200) score = Math.max(score, 55);
                        else if (articles > 100) score = Math.max(score, 40);
                        else if (articles > 50) score = Math.max(score, 25);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("GDELT fallback unavailable for conflict scoring: {}", e.getMessage());
        }

        // Signal 2: Situation Detection — VIOLENCE_ESCALATION or DISPLACEMENT_SURGE
        try {
            SituationDetectionService.SituationReport report =
                    (SituationDetectionService.SituationReport) cacheWarmupService.getFallback("activeSituations");
            if (report != null && report.getSituations() != null) {
                for (SituationDetectionService.Situation sit : report.getSituations()) {
                    if (!iso3.equals(sit.getIso3())) continue;

                    if ("VIOLENCE_ESCALATION".equals(sit.getSituationType())) {
                        if ("CRITICAL".equals(sit.getSeverity())) score += 25;
                        else if ("HIGH".equals(sit.getSeverity())) score += 18;
                        else score += 10;

                        if ("WORSENING".equals(sit.getTrajectory())) score += 5;
                    } else if ("DISPLACEMENT_SURGE".equals(sit.getSituationType())) {
                        // Forced displacement often caused by violence
                        if ("CRITICAL".equals(sit.getSeverity())) score += 10;
                        else if ("HIGH".equals(sit.getSeverity())) score += 5;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Situation data unavailable for conflict scoring: {}", e.getMessage());
        }

        // Signal 3: News briefing — count CONFLICT-topic headlines matching this country
        try {
            NewsAggregatorService.DailyBriefing briefing =
                    (NewsAggregatorService.DailyBriefing) cacheWarmupService.getFallback("dailyBriefing");
            if (briefing != null) {
                int conflictMentions = 0;

                // Check priority alerts
                if (briefing.getPriorityAlerts() != null) {
                    for (NewsAggregatorService.NewsItem item : briefing.getPriorityAlerts()) {
                        if (matchesCountryConflict(item, iso3)) {
                            conflictMentions += "CRITICAL".equals(item.getPriority()) ? 2 : 1;
                        }
                    }
                }

                // Check regional briefing headlines
                if (briefing.getRegionalBriefings() != null) {
                    for (NewsAggregatorService.RegionalBriefing rb : briefing.getRegionalBriefings()) {
                        if (rb.getNewsItems() != null) {
                            for (NewsAggregatorService.NewsItem item : rb.getNewsItems()) {
                                if (matchesCountryConflict(item, iso3)) {
                                    conflictMentions++;
                                }
                            }
                        }
                    }
                }

                // News volume conflict floor: many conflict headlines = confirmed active conflict
                if (conflictMentions >= 5) score = Math.max(score, 50);
                else if (conflictMentions >= 3) score = Math.max(score, 35);
                else if (conflictMentions >= 1) score += 5;
            }
        } catch (Exception e) {
            log.debug("News briefing unavailable for conflict scoring: {}", e.getMessage());
        }

        // Cap for countries WITHOUT verified conflict baseline.
        // GDELT/news can inflate conflict scores for countries with media attention
        // but no actual armed conflict (Cuba, Peru, El Salvador).
        // Countries WITH baseline: no cap (war is confirmed, signals add to it).
        // Countries WITHOUT baseline: max 30 from live signals alone.
        int baseline = CONFLICT_BASELINE.getOrDefault(iso3, 0);
        if (baseline == 0 && score > 30) {
            log.debug("Conflict cap for {} (no baseline): {} → 30", iso3, score);
            score = 30;
        }

        return Math.min(100, score);
    }

    /**
     * Check if a news item is about conflict AND matches a specific country.
     */
    private boolean matchesCountryConflict(NewsAggregatorService.NewsItem item, String iso3) {
        // Must have CONFLICT topic
        if (item.getTopics() == null || !item.getTopics().contains("CONFLICT")) {
            return false;
        }

        // Check by country code
        if (item.getCountries() != null) {
            for (String c : item.getCountries()) {
                if (iso3.equalsIgnoreCase(c)) return true;
            }
        }

        // Check by headline/description matching country aliases
        String text = (item.getTitle() != null ? item.getTitle() : "") + " "
                + (item.getDescription() != null ? item.getDescription() : "");
        return com.crisismonitor.config.MonitoredCountries.headlineMatchesCountry(text, iso3);
    }
}
