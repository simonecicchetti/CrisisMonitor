package com.crisismonitor.service;

import com.crisismonitor.model.NowcastResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * EXPERIMENTAL: Market Signal Analysis
 *
 * Cross-references Nowcast ML food insecurity predictions with commodity prices
 * to detect demand pressure signals that may lead commodity price movements.
 *
 * Hypothesis: when food insecurity worsens in import-dependent countries,
 * those countries increase food imports → demand pressure → commodity prices rise.
 * The Nowcast model sees consumption deterioration 4-8 weeks before markets price it.
 *
 * The Demand Pressure Index (DPI) quantifies this signal per commodity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketSignalService {

    private final NowcastService nowcastService;
    private final FAOFoodPriceService faoFoodPriceService;
    private final CacheWarmupService cacheWarmupService;
    private final FirestoreService firestoreService;
    private final ObjectMapper objectMapper;

    // Import dependency: ISO3 → estimated annual import volume in million tonnes
    // Source: FAO GIEWS, USDA FAS, trade databases
    private static final Map<String, Double> WHEAT_IMPORTERS = Map.ofEntries(
        Map.entry("EGY", 13.0), Map.entry("BGD", 7.0), Map.entry("YEM", 3.5),
        Map.entry("AFG", 3.0), Map.entry("MAR", 3.0), Map.entry("IRQ", 2.5),
        Map.entry("SDN", 2.5), Map.entry("TUN", 1.5), Map.entry("ETH", 1.5),
        Map.entry("LBY", 1.2), Map.entry("JOR", 1.0), Map.entry("SOM", 1.0),
        Map.entry("CUB", 0.8), Map.entry("LBN", 0.6), Map.entry("MOZ", 0.5),
        Map.entry("HTI", 0.3), Map.entry("DJI", 0.1), Map.entry("COM", 0.05)
    );

    private static final Map<String, Double> RICE_IMPORTERS = Map.ofEntries(
        Map.entry("CIV", 1.5), Map.entry("BGD", 1.5), Map.entry("SEN", 1.2),
        Map.entry("GHA", 0.7), Map.entry("GIN", 0.4), Map.entry("CMR", 0.4),
        Map.entry("HTI", 0.4), Map.entry("SOM", 0.3), Map.entry("NER", 0.3),
        Map.entry("SLE", 0.3), Map.entry("MOZ", 0.3), Map.entry("CUB", 0.3),
        Map.entry("LBR", 0.2), Map.entry("NPL", 0.2), Map.entry("TLS", 0.05)
    );

    private static final Map<String, Double> OIL_IMPORTERS = Map.ofEntries(
        Map.entry("PAK", 3.5), Map.entry("BGD", 2.0), Map.entry("EGY", 1.5),
        Map.entry("MMR", 0.5), Map.entry("AFG", 0.3), Map.entry("ETH", 0.3),
        Map.entry("KEN", 0.3), Map.entry("NPL", 0.3), Map.entry("SDN", 0.2),
        Map.entry("KHM", 0.2), Map.entry("LKA", 0.2), Map.entry("UGA", 0.2),
        Map.entry("YEM", 0.2), Map.entry("SOM", 0.1), Map.entry("HTI", 0.1),
        Map.entry("MOZ", 0.1)
    );

    // Maize importers — critical for East/Southern Africa
    private static final Map<String, Double> MAIZE_IMPORTERS = Map.ofEntries(
        Map.entry("KEN", 1.0), Map.entry("ZWE", 0.8), Map.entry("MWI", 0.3),
        Map.entry("MOZ", 0.4), Map.entry("ZMB", 0.2), Map.entry("UGA", 0.3),
        Map.entry("ETH", 0.5), Map.entry("SOM", 0.2), Map.entry("SDN", 0.3),
        Map.entry("COD", 0.3), Map.entry("SWZ", 0.1), Map.entry("LSO", 0.1),
        Map.entry("CUB", 0.4), Map.entry("COL", 0.5), Map.entry("GTM", 0.3),
        Map.entry("HND", 0.2), Map.entry("SLV", 0.2)
    );

    // Exporters: ISO3 → estimated annual export volume in Mt
    // Worsening in an exporter = supply disruption risk (weighted by export volume)
    private static final Map<String, Double> WHEAT_EXPORTERS = Map.of("UKR", 18.0);
    private static final Map<String, Double> MAIZE_EXPORTERS = Map.of("UKR", 25.0);

    @Data
    public static class MarketSignalReport {
        private String date;
        private String generatedAt;
        private List<CommoditySignal> signals;
        private List<ValidationRecord> validationHistory;
        private int dataPointsCollected;
        private String methodology;
    }

    @Data
    public static class CommoditySignal {
        private String commodity;
        private double currentPrice;
        private double price6mAgo;
        private double price12mAgo;
        private double priceTrend6m;
        private double demandPressureIndex;
        private String signalStrength;
        private String direction;
        private List<CountryContribution> topContributors;
        private String exporterRisk;
        private String interpretation;
        private List<Double> sparkline;    // Last 6 monthly prices for mini chart
        private String predictionHeadline; // Bold one-line prediction
    }

    @Data
    public static class ValidationRecord {
        private String commodity;
        private String predictionDate;     // When we made the prediction
        private double dpiAtPrediction;
        private String strengthAtPrediction;
        private double priceAtPrediction;
        private String priceAtPredictionDate; // FAO month
        private double priceNow;
        private String priceNowDate;
        private double priceChangePercent;
        private String outcome;            // CONFIRMED, CONTRADICTED, PENDING
        private int lagWeeks;
    }

    @Data
    public static class CountryContribution {
        private String countryName;
        private String iso3;
        private double predictedChange;    // From nowcast
        private double importVolume;       // Mt/year
        private double contribution;       // change × volume
        private String type;               // IMPORTER or EXPORTER
    }

    private static final int MARKET_SIGNAL_VERSION = 7;

    public MarketSignalReport getMarketSignals() {
        String docId = "market_" + LocalDate.now();

        // Cache check — skip if old version (missing maize, wrong exporter weights)
        Map<String, Object> cached = firestoreService.getDocument("marketSignals", docId);
        if (cached != null && cached.containsKey("signals")) {
            int cachedVersion = cached.containsKey("version") ? ((Number) cached.get("version")).intValue() : 0;
            if (cachedVersion >= MARKET_SIGNAL_VERSION) {
                return objectMapper.convertValue(cached, MarketSignalReport.class);
            }
            log.info("Market signal cache outdated (v{} < v{}), regenerating", cachedVersion, MARKET_SIGNAL_VERSION);
        }

        MarketSignalReport report = generateReport();
        if (report != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.convertValue(report, Map.class);
                data.put("timestamp", System.currentTimeMillis());
                data.put("version", MARKET_SIGNAL_VERSION);
                firestoreService.saveDocument("marketSignals", docId, data);
            } catch (Exception e) {
                log.error("Failed to save market signals: {}", e.getMessage());
            }
        }
        return report;
    }

    private MarketSignalReport generateReport() {
        // Get nowcast predictions
        var predictions = nowcastService.getNowcastAll();
        if (predictions == null || predictions.isEmpty()) {
            log.warn("No nowcast data available for market signals");
            return null;
        }

        // Build country change map
        Map<String, Double> changeMap = new HashMap<>();
        Map<String, String> nameMap = new HashMap<>();
        for (var p : predictions) {
            if (p.getPredictedChange90d() != null) {
                changeMap.put(p.getIso3(), p.getPredictedChange90d());
                nameMap.put(p.getIso3(), p.getCountryName());
            }
        }

        // Get FAO data
        var fao = faoFoodPriceService.getLatest();
        var faoTrend = faoFoodPriceService.getRecent(24);

        MarketSignalReport report = new MarketSignalReport();
        report.setDate(LocalDate.now().toString());
        report.setGeneratedAt(java.time.Instant.now().toString());
        report.setSignals(new ArrayList<>());

        // Calculate signals for each commodity
        report.getSignals().add(calculateSignal("Cereals (Wheat)", WHEAT_IMPORTERS, WHEAT_EXPORTERS,
            changeMap, nameMap, fao, faoTrend, "cerealsIndex"));
        report.getSignals().add(calculateSignal("Maize", MAIZE_IMPORTERS, MAIZE_EXPORTERS,
            changeMap, nameMap, fao, faoTrend, "cerealsIndex")); // FAO cereals includes maize
        report.getSignals().add(calculateSignal("Rice", RICE_IMPORTERS, Map.of(),
            changeMap, nameMap, fao, faoTrend, "cerealsIndex")); // FAO cereals includes rice (approx)
        report.getSignals().add(calculateSignal("Vegetable Oils", OIL_IMPORTERS, Map.of(),
            changeMap, nameMap, fao, faoTrend, "oilsIndex"));

        // Save daily snapshot to history
        saveDailySnapshot(report.getSignals());

        // Compute validation: deep retrospective (Firestore proxy history) + forward (daily snapshots)
        List<ValidationRecord> validation = new ArrayList<>();
        validation.addAll(computeDeepRetrospective(faoTrend));
        validation.addAll(computeValidation(report.getSignals()));
        report.setValidationHistory(validation);
        report.setDataPointsCollected(countHistoryDays());

        report.setMethodology("Proprietary analysis combining real-time food consumption data from 80 countries " +
            "with commodity market indicators. Demand-side signals only — supply factors not modeled. " +
            "This analysis identifies correlation patterns, not proven causation.");

        log.info("Market signals generated: {} commodities, {} validation records, {} days of history",
            report.getSignals().size(), validation.size(), report.getDataPointsCollected());
        return report;
    }

    /**
     * Save today's DPI + prices as a historical data point.
     */
    private void saveDailySnapshot(List<CommoditySignal> signals) {
        String docId = "history_" + LocalDate.now();
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("date", LocalDate.now().toString());
            snapshot.put("timestamp", System.currentTimeMillis());

            for (CommoditySignal s : signals) {
                String key = s.getCommodity().toLowerCase().replaceAll("[^a-z]", "_");
                Map<String, Object> commodityData = new LinkedHashMap<>();
                commodityData.put("dpi", s.getDemandPressureIndex());
                commodityData.put("strength", s.getSignalStrength());
                commodityData.put("price", s.getCurrentPrice());
                commodityData.put("direction", s.getDirection());
                snapshot.put(key, commodityData);
            }

            firestoreService.saveDocument("marketSignalHistory", docId, snapshot);
        } catch (Exception e) {
            log.warn("Failed to save market signal history: {}", e.getMessage());
        }
    }

    /**
     * Look back 4 and 8 weeks, compare DPI predictions with actual price changes.
     */
    private List<ValidationRecord> computeValidation(List<CommoditySignal> currentSignals) {
        List<ValidationRecord> records = new ArrayList<>();

        // Check 4-week and 8-week lookbacks
        for (int lagWeeks : new int[]{4, 8}) {
            LocalDate lookbackDate = LocalDate.now().minusWeeks(lagWeeks);
            String docId = "history_" + lookbackDate;

            Map<String, Object> pastSnapshot = firestoreService.getDocument("marketSignalHistory", docId);
            if (pastSnapshot == null) continue;

            for (CommoditySignal current : currentSignals) {
                String key = current.getCommodity().toLowerCase().replaceAll("[^a-z]", "_");
                Object pastObj = pastSnapshot.get(key);
                if (pastObj == null) continue;

                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> past = (Map<String, Object>) pastObj;
                    double pastDpi = ((Number) past.get("dpi")).doubleValue();
                    double pastPrice = ((Number) past.get("price")).doubleValue();
                    String pastStrength = (String) past.get("strength");
                    double currentPrice = current.getCurrentPrice();

                    double priceChange = pastPrice > 0 ? ((currentPrice - pastPrice) / pastPrice) * 100 : 0;

                    // Determine outcome
                    String outcome;
                    if ("STRONG".equals(pastStrength) && priceChange > 1.0) {
                        outcome = "CONFIRMED";
                    } else if ("STRONG".equals(pastStrength) && priceChange < -1.0) {
                        outcome = "CONTRADICTED";
                    } else if ("MODERATE".equals(pastStrength) && priceChange > 0) {
                        outcome = "CONFIRMED";
                    } else if ("WEAK".equals(pastStrength) && Math.abs(priceChange) < 2.0) {
                        outcome = "CONFIRMED";
                    } else if ("NONE".equals(pastStrength) && Math.abs(priceChange) < 2.0) {
                        outcome = "CONFIRMED";
                    } else if (Math.abs(priceChange) < 0.5) {
                        outcome = "NEUTRAL"; // Price didn't move enough to judge
                    } else {
                        outcome = "CONTRADICTED";
                    }

                    ValidationRecord vr = new ValidationRecord();
                    vr.setCommodity(current.getCommodity());
                    vr.setPredictionDate(lookbackDate.toString());
                    vr.setDpiAtPrediction(pastDpi);
                    vr.setStrengthAtPrediction(pastStrength);
                    vr.setPriceAtPrediction(pastPrice);
                    vr.setPriceAtPredictionDate(String.valueOf(past.getOrDefault("priceDate", lookbackDate.toString())));
                    vr.setPriceNow(currentPrice);
                    vr.setPriceNowDate(LocalDate.now().toString());
                    vr.setPriceChangePercent(Math.round(priceChange * 100.0) / 100.0);
                    vr.setOutcome(outcome);
                    vr.setLagWeeks(lagWeeks);
                    records.add(vr);
                } catch (Exception e) {
                    log.debug("Validation parse error for {}: {}", key, e.getMessage());
                }
            }
        }

        return records;
    }

    /**
     * Count days of history by checking key dates (not scanning all 90).
     */
    /**
     * Deep retrospective validation using Firestore proxy history.
     * The bootstrap saves proxy data at days_ago = {120, 105, 90, 75, 60, 45, 30, 21, 14, 7, 3, 1, 0}.
     * We reconstruct monthly DPI snapshots and compare with actual FAO price changes.
     */
    private List<ValidationRecord> computeDeepRetrospective(List<FAOFoodPriceService.FoodPriceIndex> faoTrend) {
        List<ValidationRecord> records = new ArrayList<>();
        if (faoTrend == null || faoTrend.size() < 6) return records;

        // Load all proxy history from Firestore (last 120 days)
        List<Map<String, Object>> allHistory = firestoreService.getAllProxyHistory(130);
        if (allHistory.isEmpty()) {
            log.info("No proxy history in Firestore for retrospective validation");
            return records;
        }

        // Group proxy history by date → {iso3 → proxy}
        // Approximate to monthly snapshots
        TreeMap<LocalDate, Map<String, Double>> monthlySnapshots = new TreeMap<>();
        for (Map<String, Object> record : allHistory) {
            String iso3 = (String) record.get("iso3");
            String dateStr = (String) record.get("date");
            Number proxyNum = (Number) record.get("proxy");
            if (iso3 == null || dateStr == null || proxyNum == null) continue;

            try {
                LocalDate date = LocalDate.parse(dateStr);
                // Round to nearest month start for grouping
                LocalDate monthKey = date.withDayOfMonth(1);
                monthlySnapshots.computeIfAbsent(monthKey, k -> new HashMap<>())
                    .put(iso3, proxyNum.doubleValue());
            } catch (Exception e) { /* skip bad dates */ }
        }

        // Also add current data as "today's" snapshot
        try {
            var currentPredictions = nowcastService.getNowcastAll();
            if (currentPredictions != null) {
                LocalDate todayMonth = LocalDate.now().withDayOfMonth(1);
                Map<String, Double> todaySnap = monthlySnapshots.computeIfAbsent(todayMonth, k -> new HashMap<>());
                for (var p : currentPredictions) {
                    if (p.getCurrentProxy() != null) {
                        todaySnap.put(p.getIso3(), p.getCurrentProxy());
                    }
                }
            }
        } catch (Exception e) { /* skip */ }

        if (monthlySnapshots.size() < 2) {
            log.info("Not enough monthly snapshots ({}) for deep retrospective", monthlySnapshots.size());
            return records;
        }

        log.info("Deep retrospective: {} monthly snapshots spanning {} to {}",
            monthlySnapshots.size(), monthlySnapshots.firstKey(), monthlySnapshots.lastKey());

        // Commodity definitions
        record CommodityDef(String name, Map<String, Double> importers,
                           Map<String, Double> exporters, String priceField) {}
        List<CommodityDef> commodities = List.of(
            new CommodityDef("Cereals (Wheat)", WHEAT_IMPORTERS, WHEAT_EXPORTERS, "cerealsIndex"),
            new CommodityDef("Maize", MAIZE_IMPORTERS, MAIZE_EXPORTERS, "cerealsIndex"),
            new CommodityDef("Vegetable Oils", OIL_IMPORTERS, Map.of(), "oilsIndex")
        );

        // Build FAO price lookup by month
        Map<String, FAOFoodPriceService.FoodPriceIndex> faoByMonth = new HashMap<>();
        for (var fpi : faoTrend) {
            if (fpi.getDate() != null) {
                faoByMonth.put(fpi.getDate(), fpi); // key like "2025-09"
            }
        }

        // For each consecutive pair of monthly snapshots, compute DPI
        List<LocalDate> months = new ArrayList<>(monthlySnapshots.keySet());
        for (int i = 0; i < months.size() - 1; i++) {
            LocalDate fromMonth = months.get(i);
            LocalDate toMonth = months.get(months.size() - 1); // always compare to latest

            Map<String, Double> fromSnap = monthlySnapshots.get(fromMonth);
            Map<String, Double> toSnap = monthlySnapshots.get(toMonth);

            // Find FAO prices for these months
            String fromFaoKey = String.format("%d-%02d", fromMonth.getYear(), fromMonth.getMonthValue());
            String toFaoKey = String.format("%d-%02d", toMonth.getYear(), toMonth.getMonthValue());

            // FAO data might not exist for the exact month — find closest
            FAOFoodPriceService.FoodPriceIndex fromFao = faoByMonth.get(fromFaoKey);
            FAOFoodPriceService.FoodPriceIndex toFao = faoByMonth.get(toFaoKey);
            // Fallback: use latest FAO if toMonth not found
            if (toFao == null && !faoTrend.isEmpty()) toFao = faoTrend.get(faoTrend.size() - 1);
            if (fromFao == null) continue; // Can't validate without starting price

            int lagWeeks = (int) ((toMonth.toEpochDay() - fromMonth.toEpochDay()) / 7);
            String periodLabel = fromMonth.getMonth().toString().substring(0, 3) + " " + fromMonth.getYear();

            for (CommodityDef cd : commodities) {
                double fromPrice = getPriceValue(fromFao, cd.priceField());
                double toPrice = getPriceValue(toFao, cd.priceField());
                if (fromPrice <= 0) continue;

                // Calculate DPI: change in proxy × import volume
                double retroDpi = 0;

                for (var entry : cd.importers().entrySet()) {
                    Double fromProxy = fromSnap.get(entry.getKey());
                    Double toProxy = toSnap.get(entry.getKey());
                    if (fromProxy == null || toProxy == null) continue;
                    double change = toProxy - fromProxy;
                    if (change > 0.5) {
                        retroDpi += change * entry.getValue();
                    }
                }

                for (var entry : cd.exporters().entrySet()) {
                    Double fromProxy = fromSnap.get(entry.getKey());
                    Double toProxy = toSnap.get(entry.getKey());
                    if (fromProxy == null || toProxy == null) continue;
                    double change = toProxy - fromProxy;
                    if (change > 2) {
                        retroDpi += change * entry.getValue() * 0.5;
                    }
                }

                double priceChange = ((toPrice - fromPrice) / fromPrice) * 100;
                String strength = retroDpi > 20 ? "STRONG" : retroDpi > 10 ? "MODERATE" :
                                 retroDpi > 5 ? "WEAK" : "NONE";

                String outcome;
                if ("STRONG".equals(strength) && priceChange > 0.5) outcome = "CONFIRMED";
                else if ("STRONG".equals(strength) && priceChange < -1.0) outcome = "CONTRADICTED";
                else if ("MODERATE".equals(strength) && priceChange > 0) outcome = "CONFIRMED";
                else if (("WEAK".equals(strength) || "NONE".equals(strength)) && Math.abs(priceChange) < 2.0) outcome = "CONFIRMED";
                else if ("NONE".equals(strength) && priceChange > 3.0) outcome = "MISSED";
                else if (Math.abs(priceChange) < 0.3) outcome = "NEUTRAL";
                else outcome = "MIXED";

                ValidationRecord vr = new ValidationRecord();
                vr.setCommodity(cd.name());
                vr.setPredictionDate(periodLabel);
                vr.setDpiAtPrediction(Math.round(retroDpi * 10.0) / 10.0);
                vr.setStrengthAtPrediction(strength);
                vr.setPriceAtPrediction(fromPrice);
                vr.setPriceAtPredictionDate(fromFaoKey);
                vr.setPriceNow(toPrice);
                vr.setPriceNowDate(toFao != null ? toFao.getDate() : toFaoKey);
                vr.setPriceChangePercent(Math.round(priceChange * 100.0) / 100.0);
                vr.setOutcome(outcome);
                vr.setLagWeeks(lagWeeks);
                records.add(vr);
            }
        }

        log.info("Deep retrospective: {} validation records from {} monthly snapshots", records.size(), months.size());
        return records;
    }

    private int countHistoryDays() {
        try {
            int count = 0;
            // Check every 3rd day for efficiency (estimate = count × 3)
            for (int i = 0; i < 90; i += 3) {
                String docId = "history_" + LocalDate.now().minusDays(i);
                if (firestoreService.getDocument("marketSignalHistory", docId) != null) {
                    count++;
                }
            }
            // First day is today (always exists after save), so minimum 1
            return Math.max(1, count * 3); // Approximate
        } catch (Exception e) {
            return 1;
        }
    }

    private CommoditySignal calculateSignal(String commodity, Map<String, Double> importers,
            Map<String, Double> exporters, Map<String, Double> changeMap, Map<String, String> nameMap,
            FAOFoodPriceService.FoodPriceIndex fao, List<FAOFoodPriceService.FoodPriceIndex> trend,
            String priceField) {

        CommoditySignal signal = new CommoditySignal();
        signal.setCommodity(commodity);
        signal.setTopContributors(new ArrayList<>());

        // Current price
        double currentPrice = getPriceValue(fao, priceField);
        signal.setCurrentPrice(currentPrice);

        // Historical prices: 3m, 6m, 12m ago
        if (trend != null && trend.size() >= 4) {
            int size = trend.size();
            double price3m = size >= 4 ? getPriceValue(trend.get(size - 4), priceField) : 0;
            double price6m = size >= 7 ? getPriceValue(trend.get(size - 7), priceField) : 0;
            double price12m = size >= 13 ? getPriceValue(trend.get(size - 13), priceField) : 0;
            signal.setPrice6mAgo(price6m);
            signal.setPrice12mAgo(price12m);
            signal.setPriceTrend6m(price6m > 0 ? ((currentPrice - price6m) / price6m) * 100 : 0);
            // 3m trend for recent acceleration detection
            double trend3m = price3m > 0 ? ((currentPrice - price3m) / price3m) * 100 : 0;
            // Use the STRONGER of 3m or 6m annualized for direction
            signal.setPriceTrend6m(signal.getPriceTrend6m()); // keep 6m as stored value
            // Direction uses 3m to catch recent moves
            if (trend3m > 1.5 || signal.getPriceTrend6m() > 1.5) signal.setDirection("UPWARD");
            else if (trend3m < -1.5 && signal.getPriceTrend6m() < -1.5) signal.setDirection("DOWNWARD");
            else signal.setDirection("STABLE");
        }

        // Calculate DPI from importers (demand side)
        double dpi = 0;
        for (var entry : importers.entrySet()) {
            String iso3 = entry.getKey();
            double importVol = entry.getValue();
            double change = changeMap.getOrDefault(iso3, 0.0);

            if (change > 0.5) {
                double contribution = change * importVol;
                dpi += contribution;

                CountryContribution cc = new CountryContribution();
                cc.setCountryName(nameMap.getOrDefault(iso3, iso3));
                cc.setIso3(iso3);
                cc.setPredictedChange(change);
                cc.setImportVolume(importVol);
                cc.setContribution(Math.round(contribution * 10.0) / 10.0);
                cc.setType("IMPORTER");
                signal.getTopContributors().add(cc);
            }
        }

        // Supply-side risk: exporters with worsening food insecurity
        // Weighted by EXPORT volume (not arbitrary multiplier)
        StringBuilder exporterRisk = new StringBuilder();
        for (var entry : exporters.entrySet()) {
            String iso3 = entry.getKey();
            double exportVol = entry.getValue();
            double change = changeMap.getOrDefault(iso3, 0.0);
            if (change > 2) {
                double supplyRisk = change * exportVol * 0.5; // 50% of export volume as risk weight
                exporterRisk.append(nameMap.getOrDefault(iso3, iso3))
                    .append(": food insecurity ").append(String.format("%+.1fpp", change))
                    .append(", major exporter — supply disruption risk. ");

                CountryContribution cc = new CountryContribution();
                cc.setCountryName(nameMap.getOrDefault(iso3, iso3));
                cc.setIso3(iso3);
                cc.setPredictedChange(change);
                cc.setImportVolume(exportVol); // Display export vol in the same field
                cc.setContribution(Math.round(supplyRisk * 10.0) / 10.0);
                cc.setType("EXPORTER_RISK");
                signal.getTopContributors().add(cc);
                dpi += supplyRisk;
            }
        }
        signal.setExporterRisk(exporterRisk.length() > 0 ? exporterRisk.toString().trim() : null);

        // Sort contributors by contribution, keep top 6
        signal.getTopContributors().sort((a, b) -> Double.compare(b.getContribution(), a.getContribution()));
        if (signal.getTopContributors().size() > 6) {
            signal.setTopContributors(new ArrayList<>(signal.getTopContributors().subList(0, 6)));
        }

        signal.setDemandPressureIndex(Math.round(dpi * 10.0) / 10.0);

        // Signal strength thresholds
        if (dpi > 20) signal.setSignalStrength("STRONG");
        else if (dpi > 10) signal.setSignalStrength("MODERATE");
        else if (dpi > 5) signal.setSignalStrength("WEAK");
        else signal.setSignalStrength("NONE");

        // Direction already set above with 3m/6m logic

        // Sparkline: last 6 monthly prices
        if (trend != null && trend.size() >= 6) {
            List<Double> spark = new ArrayList<>();
            int trendSize = trend.size();
            for (int i = Math.max(0, trendSize - 6); i < trendSize; i++) {
                spark.add(getPriceValue(trend.get(i), priceField));
            }
            signal.setSparkline(spark);
        }

        // Prediction headline
        signal.setPredictionHeadline(buildPredictionHeadline(signal));
        signal.setInterpretation(buildInterpretation(signal));
        return signal;
    }

    private String buildPredictionHeadline(CommoditySignal signal) {
        String strength = signal.getSignalStrength();
        String dir = signal.getDirection();
        if ("STRONG".equals(strength) && "UPWARD".equals(dir)) {
            return "Strong upward pressure — prices likely to continue rising";
        } else if ("STRONG".equals(strength)) {
            return "Strong demand pressure detected — monitor for price acceleration";
        } else if ("MODERATE".equals(strength) && "UPWARD".equals(dir)) {
            return "Moderate pressure building — prices trending upward";
        } else if ("MODERATE".equals(strength)) {
            return "Moderate demand signals — early indications of price pressure";
        } else if ("WEAK".equals(strength)) {
            return "Weak signal — limited demand pressure from monitored countries";
        }
        return "No significant demand pressure detected";
    }

    private String buildInterpretation(CommoditySignal signal) {
        String strength = signal.getSignalStrength();
        String direction = signal.getDirection();
        double dpi = signal.getDemandPressureIndex();

        if ("STRONG".equals(strength) && "UPWARD".equals(direction)) {
            return String.format("Strong demand pressure (DPI %.0f) aligns with rising prices (+%.1f%% 6m). " +
                "Consumption deterioration in major importers is likely contributing to and may accelerate price increases.",
                dpi, signal.getPriceTrend6m());
        } else if ("STRONG".equals(strength) && !"UPWARD".equals(direction)) {
            return String.format("Strong demand pressure (DPI %.0f) detected but prices not yet rising. " +
                "This divergence may signal upcoming price pressure within 4-8 weeks as import demand materializes.",
                dpi);
        } else if ("MODERATE".equals(strength)) {
            return String.format("Moderate demand pressure (DPI %.0f) from worsening food insecurity in import-dependent countries. " +
                "Monitor for acceleration — if more countries tip into worsening, signal strengthens.", dpi);
        } else if ("WEAK".equals(strength)) {
            return String.format("Weak demand signal (DPI %.0f). Limited worsening among major importers. " +
                "Current commodity price movements driven by other factors.", dpi);
        }
        return "No significant demand pressure detected from food insecurity patterns.";
    }

    private double getPriceValue(Object faoData, String field) {
        try {
            if (faoData == null) return 0;
            @SuppressWarnings("unchecked")
            Map<String, Object> map = faoData instanceof Map ? (Map<String, Object>) faoData
                : objectMapper.convertValue(faoData, Map.class);
            Object val = map.get(field);
            if (val instanceof Number) return ((Number) val).doubleValue();
        } catch (Exception e) { /* skip */ }
        return 0;
    }
}
