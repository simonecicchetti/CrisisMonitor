package com.crisismonitor.service;

import ai.onnxruntime.*;
import com.crisismonitor.config.MonitoredCountries;
import com.crisismonitor.model.FoodSecurityMetrics;
import com.crisismonitor.model.NowcastResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Food insecurity nowcasting service.
 *
 * Uses a LightGBM model (exported as ONNX) trained on WFP HungerMap LIVE data
 * to nowcast 90-day % change in food insecurity proxy per country.
 *
 * The proxy = avg(FCG<=2%, rCSI>=19%) from WFP near-real-time phone surveys.
 *
 * Model: autoregressive (uses food insecurity history to predict trajectory).
 * R2=0.98, MAE=1.6 percentage points, direction accuracy=97.7% on test set.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NowcastService {

    private final HungerMapService hungerMapService;
    private final FirestoreService firestoreService;

    private OrtEnvironment env;
    private OrtSession session;
    private final List<OrtSession> ensembleSessions = new ArrayList<>();

    // Store daily proxy values per country for feature computation
    // Key: iso3, Value: sorted map of date -> proxy value
    private final Map<String, TreeMap<LocalDate, Double>> proxyHistory = new ConcurrentHashMap<>();

    // 26 features expected by the model (in order)
    private static final String[] FEATURE_NAMES = {
        "proxy_current", "fcg_current", "rcsi_current",
        "proxy_lag_7d", "proxy_lag_14d", "proxy_lag_30d", "proxy_lag_60d", "proxy_lag_90d",
        "proxy_rolling_mean_7d", "proxy_rolling_mean_14d", "proxy_rolling_mean_30d",
        "proxy_rolling_mean_60d", "proxy_rolling_mean_90d",
        "proxy_rolling_std_7d", "proxy_rolling_std_14d", "proxy_rolling_std_30d",
        "proxy_rolling_std_60d", "proxy_rolling_std_90d",
        "proxy_change_7d", "proxy_change_14d", "proxy_change_30d",
        "proxy_trend_30d", "proxy_volatility_30d",
        "month_sin", "month_cos",
        "sample_quality",
        "is_lean_season" // V2: agricultural lean season indicator
    };

    // Lean season months per country (period between harvests when food is scarce)
    private static final Map<String, int[]> LEAN_SEASONS = Map.ofEntries(
        Map.entry("MLI", new int[]{6,7,8}), Map.entry("BFA", new int[]{6,7,8}),
        Map.entry("NER", new int[]{6,7,8}), Map.entry("TCD", new int[]{6,7,8}),
        Map.entry("NGA", new int[]{6,7,8}), Map.entry("CMR", new int[]{6,7,8}),
        Map.entry("ETH", new int[]{3,4,5,10,11}), Map.entry("SOM", new int[]{3,4,5,10,11}),
        Map.entry("KEN", new int[]{3,4,5,10,11}), Map.entry("UGA", new int[]{1,2,3}),
        Map.entry("SSD", new int[]{5,6,7}), Map.entry("SDN", new int[]{6,7,8}),
        Map.entry("COD", new int[]{2,3,4}), Map.entry("CAF", new int[]{5,6,7}),
        Map.entry("MOZ", new int[]{1,2,3}), Map.entry("ZWE", new int[]{1,2,3}),
        Map.entry("AFG", new int[]{3,4,5}), Map.entry("HTI", new int[]{3,4,5}),
        Map.entry("GTM", new int[]{4,5,6,7}), Map.entry("HND", new int[]{5,6,7}),
        Map.entry("SLV", new int[]{5,6,7}), Map.entry("NIC", new int[]{5,6,7})
    );

    @PostConstruct
    public void init() {
        try {
            env = OrtEnvironment.getEnvironment();

            // Load ensemble models (3-model: base + quantile + xgboost)
            // Huber removed — it degraded ensemble performance (MAE 5.12 individually,
            // crisis detection only 85.9%). 3-model achieves MAE 1.29 vs 4-model's 1.95.
            String[] ensembleFiles = {
                "ml/ensemble_base.onnx",
                "ml/ensemble_quantile.onnx",
                "ml/ensemble_xgboost.onnx"
            };
            for (String file : ensembleFiles) {
                try {
                    ClassPathResource res = new ClassPathResource(file);
                    try (InputStream is = res.getInputStream()) {
                        byte[] bytes = is.readAllBytes();
                        OrtSession s = env.createSession(bytes, new OrtSession.SessionOptions());
                        ensembleSessions.add(s);
                    }
                } catch (Exception e) {
                    log.warn("Failed to load ensemble model {}: {}", file, e.getMessage());
                }
            }

            // Fallback: load single model if ensemble fails
            if (ensembleSessions.isEmpty()) {
                ClassPathResource resource = new ClassPathResource("ml/nowcast_model.onnx");
                try (InputStream is = resource.getInputStream()) {
                    byte[] modelBytes = is.readAllBytes();
                    session = env.createSession(modelBytes, new OrtSession.SessionOptions());
                }
                log.info("Loaded single nowcast model (fallback)");
            } else {
                session = ensembleSessions.get(0); // Primary for isReady() check
                log.info("Loaded {} ensemble models for nowcasting", ensembleSessions.size());
            }
        } catch (Exception e) {
            log.error("Failed to load nowcast model: {}", e.getMessage());
        }

        // Load proxy history from Firestore (survives deploys)
        loadHistoryFromFirestore();

        // If no history yet, bootstrap from HungerMap API in background (non-blocking)
        if (proxyHistory.isEmpty() || proxyHistory.values().stream().allMatch(m -> m.size() < 30)) {
            new Thread(() -> {
                try {
                    Thread.sleep(30000); // Wait 30s for app to fully start
                    bootstrapFromHungerMap();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "nowcast-bootstrap").start();
        }
    }

    private void loadHistoryFromFirestore() {
        try {
            List<Map<String, Object>> records = firestoreService.getAllProxyHistory(120);
            if (records.isEmpty()) {
                log.info("No proxy history in Firestore (first run)");
                return;
            }
            int count = 0;
            for (Map<String, Object> record : records) {
                String iso3 = (String) record.get("iso3");
                String dateStr = (String) record.get("date");
                Number proxyNum = (Number) record.get("proxy");
                if (iso3 != null && dateStr != null && proxyNum != null) {
                    LocalDate date = LocalDate.parse(dateStr);
                    proxyHistory.computeIfAbsent(iso3, k -> new TreeMap<>())
                        .put(date, proxyNum.doubleValue());
                    count++;
                }
            }
            log.info("Loaded {} proxy history records from Firestore for {} countries",
                count, proxyHistory.size());
        } catch (Exception e) {
            log.warn("Failed to load proxy history from Firestore: {}", e.getMessage());
        }
    }

    private void bootstrapFromHungerMap() {
        log.info("Bootstrapping proxy history from HungerMap API (days_ago)...");
        int[] daysAgoList = {120, 105, 90, 75, 60, 45, 30, 21, 14, 7, 3, 1, 0};
        int totalRecorded = 0;

        for (int daysAgo : daysAgoList) {
            try {
                List<FoodSecurityMetrics> metrics = hungerMapService.getFoodSecurityMetricsForDaysAgo(daysAgo);
                if (metrics == null || metrics.isEmpty()) continue;

                LocalDate date = LocalDate.now().minusDays(daysAgo);
                String dateStr = date.toString();
                int count = 0;

                for (FoodSecurityMetrics m : metrics) {
                    if (m.getIso3() == null) continue;
                    Double fcs = m.getFcsPrevalence();
                    Double rcsi = m.getRcsiPrevalence();
                    double proxy;
                    if (fcs != null && rcsi != null) {
                        proxy = (fcs * 100 + rcsi * 100) / 2.0;
                    } else if (fcs != null) {
                        proxy = fcs * 100;
                    } else {
                        continue;
                    }
                    proxyHistory.computeIfAbsent(m.getIso3(), k -> new TreeMap<>())
                        .put(date, proxy);
                    firestoreService.saveProxyDaily(m.getIso3(), dateStr, proxy,
                        fcs != null ? fcs * 100 : null, rcsi != null ? rcsi * 100 : null);
                    count++;
                }
                totalRecorded += count;
                log.info("  Bootstrap days_ago={}: {} countries", daysAgo, count);
                Thread.sleep(2000);
            } catch (Exception e) {
                log.warn("  Bootstrap days_ago={} failed: {}", daysAgo, e.getMessage());
            }
        }
        log.info("Bootstrap complete: {} records, {} countries", totalRecorded, proxyHistory.size());
    }

    @PreDestroy
    public void cleanup() {
        if (session != null) {
            try { session.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    /**
     * Record today's food security data for building up history.
     * Called by CacheWarmupService after fetching HungerMap data.
     */
    public void recordDailyData(List<FoodSecurityMetrics> metrics) {
        LocalDate today = LocalDate.now();
        String todayStr = today.toString();
        int saved = 0;
        for (FoodSecurityMetrics m : metrics) {
            if (m.getIso3() == null) continue;
            Double fcs = m.getFcsPrevalence();
            Double rcsi = m.getRcsiPrevalence();
            double proxy;
            // HungerMap API returns prevalence as 0-1, convert to 0-100 for model
            if (fcs != null && rcsi != null) {
                proxy = (fcs * 100 + rcsi * 100) / 2.0;
            } else if (fcs != null) {
                proxy = fcs * 100;
            } else {
                continue;
            }
            proxyHistory.computeIfAbsent(m.getIso3(), k -> new TreeMap<>())
                .put(today, proxy);

            // Persist to Firestore (permanent storage)
            firestoreService.saveProxyDaily(m.getIso3(), todayStr, proxy,
                fcs != null ? fcs * 100 : null,
                rcsi != null ? rcsi * 100 : null);
            saved++;
        }
        log.info("Recorded daily food security data for {} countries (persisted to Firestore)", saved);
    }

    /**
     * Get nowcast predictions for all monitored countries.
     */
    @Cacheable(value = "nowcast", key = "'all'")
    public List<NowcastResult> getNowcastAll() {
        if (session == null) {
            log.warn("Nowcast model not loaded");
            return Collections.emptyList();
        }

        // Get current food security data
        List<FoodSecurityMetrics> metrics = hungerMapService.getFoodSecurityMetrics();
        if (metrics == null || metrics.isEmpty()) {
            log.warn("No food security metrics available for nowcasting");
            return Collections.emptyList();
        }

        // Record today's data
        recordDailyData(metrics);

        List<NowcastResult> results = new ArrayList<>();
        for (FoodSecurityMetrics m : metrics) {
            String iso3 = m.getIso3();
            if (iso3 == null) continue;

            NowcastResult result = predict(iso3, m);
            if (result != null) {
                results.add(result);
            }
        }

        // Sort by predicted change (worst deterioration first)
        results.sort((a, b) -> {
            Double changeA = a.getPredictedChange90d();
            Double changeB = b.getPredictedChange90d();
            if (changeA == null) return 1;
            if (changeB == null) return -1;
            return Double.compare(changeB, changeA);
        });

        // Persist predictions for ground truthing
        String todayStr = LocalDate.now().toString();
        for (NowcastResult r : results) {
            if (r.getPredictedChange90d() != null && r.getCurrentProxy() != null) {
                firestoreService.saveNowcastPrediction(
                    r.getIso3(), todayStr, r.getCurrentProxy(),
                    r.getPredictedChange90d(),
                    r.getTrend(), r.getConfidence());
            }
        }

        log.info("Nowcast complete: {} countries, {} with predictions (persisted to Firestore)",
            metrics.size(), results.stream().filter(r -> r.getPredictedChange90d() != null).count());
        return results;
    }

    private NowcastResult predict(String iso3, FoodSecurityMetrics metrics) {
        Double fcs = metrics.getFcsPrevalence();
        Double rcsi = metrics.getRcsiPrevalence();

        // Compute proxy
        double proxy;
        if (fcs != null && rcsi != null) {
            proxy = (fcs + rcsi) / 2.0;
        } else if (fcs != null) {
            proxy = fcs;
        } else if (rcsi != null) {
            proxy = rcsi;
        } else {
            return buildResult(iso3, metrics, null, null);
        }

        // HungerMap returns prevalence as 0-1, our model uses 0-100
        double proxyPct = proxy * 100.0;
        double fcsPct = fcs != null ? fcs * 100.0 : proxyPct;
        double rcsiPct = rcsi != null ? rcsi * 100.0 : proxyPct;

        // Get history for this country
        TreeMap<LocalDate, Double> history = proxyHistory.get(iso3);
        LocalDate today = LocalDate.now();

        // Build feature vector
        float[] features = new float[FEATURE_NAMES.length];
        features[0] = (float) proxyPct;  // proxy_current
        features[1] = (float) fcsPct;    // fcg_current
        features[2] = (float) rcsiPct;   // rcsi_current

        if (history != null && history.size() >= 7) {
            // Lagged values
            features[3] = (float) getHistoryValue(history, today, 7);   // lag_7d
            features[4] = (float) getHistoryValue(history, today, 14);  // lag_14d
            features[5] = (float) getHistoryValue(history, today, 30);  // lag_30d
            features[6] = (float) getHistoryValue(history, today, 60);  // lag_60d
            features[7] = (float) getHistoryValue(history, today, 90);  // lag_90d

            // Rolling means
            features[8] = (float) rollingMean(history, today, 7);
            features[9] = (float) rollingMean(history, today, 14);
            features[10] = (float) rollingMean(history, today, 30);
            features[11] = (float) rollingMean(history, today, 60);
            features[12] = (float) rollingMean(history, today, 90);

            // Rolling stds
            features[13] = (float) rollingStd(history, today, 7);
            features[14] = (float) rollingStd(history, today, 14);
            features[15] = (float) rollingStd(history, today, 30);
            features[16] = (float) rollingStd(history, today, 60);
            features[17] = (float) rollingStd(history, today, 90);

            // Changes
            double lag7 = getHistoryValue(history, today, 7);
            double lag14 = getHistoryValue(history, today, 14);
            double lag30 = getHistoryValue(history, today, 30);
            features[18] = lag7 > 1 ? (float) ((proxyPct - lag7) / lag7 * 100) : 0;
            features[19] = lag14 > 1 ? (float) ((proxyPct - lag14) / lag14 * 100) : 0;
            features[20] = lag30 > 1 ? (float) ((proxyPct - lag30) / lag30 * 100) : 0;

            // Trend (linear slope over 30 days)
            features[21] = (float) computeTrend(history, today, 30);

            // Volatility
            double mean30 = rollingMean(history, today, 30);
            double std30 = rollingStd(history, today, 30);
            features[22] = mean30 > 0 ? (float) (std30 / mean30) : 0;
        } else {
            // Not enough history — use current value as all lags (conservative estimate)
            for (int i = 3; i <= 7; i++) features[i] = (float) proxyPct;
            for (int i = 8; i <= 12; i++) features[i] = (float) proxyPct;
            // stds = 0, changes = 0, trend = 0, volatility = 0 (already 0)
        }

        // Seasonality
        int month = today.getMonthValue();
        features[23] = (float) Math.sin(2 * Math.PI * month / 12.0);
        features[24] = (float) Math.cos(2 * Math.PI * month / 12.0);

        // Sample quality (default 1.0 = good)
        features[25] = 1.0f;

        // V2: Lean season indicator (agricultural calendar)
        int[] leanMonths = LEAN_SEASONS.get(iso3);
        if (leanMonths != null) {
            for (int lm : leanMonths) {
                if (lm == month) { features[26] = 1.0f; break; }
            }
        }

        // Run ensemble inference (average of multiple models)
        try {
            float[][] input = new float[][] { features };
            OnnxTensor tensor = OnnxTensor.createTensor(env, input);

            double sumPredictions = 0;
            int modelCount = 0;

            List<OrtSession> sessions = ensembleSessions.isEmpty() ? List.of(session) : ensembleSessions;
            for (OrtSession s : sessions) {
                try {
                    Map<String, OnnxTensor> inputs = Map.of(s.getInputNames().iterator().next(), tensor);
                    try (OrtSession.Result result = s.run(inputs)) {
                        float[] output = ((float[][]) result.get(0).getValue())[0];
                        sumPredictions += output[0];
                        modelCount++;
                    }
                } catch (Exception e) {
                    log.debug("Ensemble model failed, skipping: {}", e.getMessage());
                }
            }

            tensor.close();

            if (modelCount > 0) {
                double predictedChange = sumPredictions / modelCount;
                return buildResult(iso3, metrics, proxyPct, predictedChange);
            }
            return buildResult(iso3, metrics, proxyPct, null);
        } catch (Exception e) {
            log.error("Nowcast inference failed for {}: {}", iso3, e.getMessage());
            return buildResult(iso3, metrics, proxyPct, null);
        }
    }

    private NowcastResult buildResult(String iso3, FoodSecurityMetrics metrics,
                                       Double proxyPct, Double predictedChange) {
        TreeMap<LocalDate, Double> history = proxyHistory.get(iso3);
        LocalDate today = LocalDate.now();

        NowcastResult.NowcastResultBuilder builder = NowcastResult.builder()
            .iso3(iso3)
            .countryName(metrics.getName() != null ? metrics.getName() : MonitoredCountries.getName(iso3))
            .region(getRegionForCountry(iso3))
            .fcsPrevalence(metrics.getFcsPrevalence() != null ? metrics.getFcsPrevalence() * 100 : null)
            .rcsiPrevalence(metrics.getRcsiPrevalence() != null ? metrics.getRcsiPrevalence() * 100 : null)
            .currentProxy(proxyPct);

        if (predictedChange != null) {
            builder.predictedChange90d(Math.round(predictedChange * 100.0) / 100.0);

            // Projected proxy
            if (proxyPct != null) {
                double projected = proxyPct * (1 + predictedChange / 100.0);
                builder.projectedProxy(Math.round(projected * 100.0) / 100.0);
            }

            // Trend classification
            if (predictedChange > 3) builder.trend("WORSENING");
            else if (predictedChange < -3) builder.trend("IMPROVING");
            else builder.trend("STABLE");

            // Confidence based on history depth
            if (history != null && history.size() >= 90) builder.confidence("HIGH");
            else if (history != null && history.size() >= 30) builder.confidence("MEDIUM");
            else builder.confidence("LOW");
        }

        // Historical values
        if (history != null && !history.isEmpty()) {
            builder.proxy30dAgo(getHistoryValue(history, today, 30));
            builder.proxy60dAgo(getHistoryValue(history, today, 60));
            builder.proxy90dAgo(getHistoryValue(history, today, 90));

            double current = proxyPct != null ? proxyPct : 0;
            double ago30 = getHistoryValue(history, today, 30);
            if (ago30 > 1) {
                builder.actualChange30d(Math.round((current - ago30) / ago30 * 10000.0) / 100.0);
            }
        }

        return builder.build();
    }

    private double getHistoryValue(TreeMap<LocalDate, Double> history, LocalDate from, int daysAgo) {
        LocalDate target = from.minusDays(daysAgo);
        // Find closest date within ±3 days
        Map.Entry<LocalDate, Double> entry = history.floorEntry(target.plusDays(3));
        if (entry != null && !entry.getKey().isBefore(target.minusDays(3))) {
            return entry.getValue();
        }
        // Fallback: most recent available
        Map.Entry<LocalDate, Double> latest = history.lastEntry();
        return latest != null ? latest.getValue() : 0;
    }

    private double rollingMean(TreeMap<LocalDate, Double> history, LocalDate from, int days) {
        LocalDate start = from.minusDays(days);
        var sub = history.subMap(start, true, from, true);
        if (sub.isEmpty()) return 0;
        return sub.values().stream().mapToDouble(d -> d).average().orElse(0);
    }

    private double rollingStd(TreeMap<LocalDate, Double> history, LocalDate from, int days) {
        LocalDate start = from.minusDays(days);
        var sub = history.subMap(start, true, from, true);
        if (sub.size() < 2) return 0;
        double mean = sub.values().stream().mapToDouble(d -> d).average().orElse(0);
        double variance = sub.values().stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0);
        return Math.sqrt(variance);
    }

    private double computeTrend(TreeMap<LocalDate, Double> history, LocalDate from, int days) {
        LocalDate start = from.minusDays(days);
        var sub = history.subMap(start, true, from, true);
        if (sub.size() < 5) return 0;

        // Simple linear regression slope
        List<Double> values = new ArrayList<>(sub.values());
        int n = values.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += values.get(i);
            sumXY += i * values.get(i);
            sumX2 += i * i;
        }
        double denom = n * sumX2 - sumX * sumX;
        if (Math.abs(denom) < 0.001) return 0;
        return (n * sumXY - sumX * sumY) / denom;
    }

    // Extended region mapping for all WFP HungerMap countries
    private static final Map<String, String> REGION_MAP = new HashMap<>();
    static {
        // Africa - East
        for (String c : List.of("ETH","KEN","SOM","SSD","UGA","TZA","RWA","BDI","DJI","ERI","MDG","MWI","MOZ","ZMB","ZWE","COM","MUS"))
            REGION_MAP.put(c, "East Africa");
        // Africa - West
        for (String c : List.of("NGA","MLI","BFA","NER","TCD","GHA","SEN","GIN","SLE","LBR","CIV","TGO","BEN","GMB","GNB","CPV","MRT","STP"))
            REGION_MAP.put(c, "West Africa");
        // Africa - Central
        for (String c : List.of("COD","CAF","CMR","COG","GAB","GNQ"))
            REGION_MAP.put(c, "Central Africa");
        // Africa - Southern
        for (String c : List.of("AGO","NAM","BWA","SWZ","LSO","ZAF"))
            REGION_MAP.put(c, "Southern Africa");
        // MENA
        for (String c : List.of("SYR","IRQ","YEM","LBN","PSE","IRN","ISR","LBY","EGY","TUN","MAR","JOR","DZA"))
            REGION_MAP.put(c, "MENA");
        // Asia - South
        for (String c : List.of("AFG","PAK","BGD","NPL","LKA","BTN","IND","MMR"))
            REGION_MAP.put(c, "South Asia");
        // Asia - East & Southeast
        for (String c : List.of("KHM","LAO","VNM","IDN","PHL","TLS","MNG","KGZ","TJK","UZB"))
            REGION_MAP.put(c, "East & SE Asia");
        // LAC
        for (String c : List.of("HTI","VEN","COL","GTM","HND","SLV","NIC","MEX","PER","ECU","CUB","PAN","BOL","DOM","BRA","ARG","SUR","GUY","BLZ"))
            REGION_MAP.put(c, "LAC");
        // Europe
        for (String c : List.of("UKR","MDA","ARM","GEO"))
            REGION_MAP.put(c, "Europe");
        // Pacific
        for (String c : List.of("FJI","VUT","SLB","PNG","WSM","TON"))
            REGION_MAP.put(c, "Pacific");
    }

    private String getRegionForCountry(String iso3) {
        String region = REGION_MAP.get(iso3);
        if (region != null) return region;
        // Fallback to MonitoredCountries
        String mcRegion = MonitoredCountries.getRegion(iso3);
        if (!"Global".equals(mcRegion)) return mcRegion;
        return "Other";
    }

    /**
     * Check if the model is loaded and ready.
     */
    public boolean isReady() {
        return session != null;
    }

    /**
     * Get the number of countries with history data.
     */
    public int getHistoryCountries() {
        return proxyHistory.size();
    }

    /**
     * Get days of history for a specific country.
     */
    public int getHistoryDays(String iso3) {
        TreeMap<LocalDate, Double> h = proxyHistory.get(iso3);
        return h != null ? h.size() : 0;
    }
}
