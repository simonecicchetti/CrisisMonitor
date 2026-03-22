package com.crisismonitor.service;

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

    // Ukraine is a special case: major EXPORTER. Worsening there = supply disruption.
    private static final Set<String> WHEAT_EXPORTERS = Set.of("UKR");

    @Data
    public static class MarketSignalReport {
        private String date;
        private String generatedAt;
        private List<CommoditySignal> signals;
        private String methodology;
    }

    @Data
    public static class CommoditySignal {
        private String commodity;
        private double currentPrice;       // FAO index value
        private double price6mAgo;         // For trend
        private double price12mAgo;
        private double priceTrend6m;       // % change
        private double demandPressureIndex;
        private String signalStrength;     // STRONG, MODERATE, WEAK, NONE
        private String direction;          // UPWARD, STABLE, DOWNWARD
        private List<CountryContribution> topContributors;
        private String exporterRisk;       // Supply-side signal (e.g., Ukraine)
        private String interpretation;     // One-line analysis
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

    public MarketSignalReport getMarketSignals() {
        String docId = "market_" + LocalDate.now();

        // Cache check
        Map<String, Object> cached = firestoreService.getDocument("marketSignals", docId);
        if (cached != null && cached.containsKey("signals")) {
            return objectMapper.convertValue(cached, MarketSignalReport.class);
        }

        MarketSignalReport report = generateReport();
        if (report != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.convertValue(report, Map.class);
                data.put("timestamp", System.currentTimeMillis());
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
        report.getSignals().add(calculateSignal("Rice", RICE_IMPORTERS, Set.of(),
            changeMap, nameMap, fao, faoTrend, "cerealsIndex")); // Rice tracked within cereals
        report.getSignals().add(calculateSignal("Vegetable Oils", OIL_IMPORTERS, Set.of(),
            changeMap, nameMap, fao, faoTrend, "oilsIndex"));

        report.setMethodology("Demand Pressure Index = Σ(predicted_food_insecurity_change × annual_import_volume) " +
            "for each commodity's import-dependent countries. " +
            "Nowcast ML predicts 90-day food insecurity changes from real-time consumption surveys. " +
            "When insecurity rises in import-dependent countries, demand for commodity imports increases, " +
            "creating upward price pressure with an estimated 4-8 week lag.");

        log.info("Market signals generated: {} commodities analyzed", report.getSignals().size());
        return report;
    }

    private CommoditySignal calculateSignal(String commodity, Map<String, Double> importers,
            Set<String> exporters, Map<String, Double> changeMap, Map<String, String> nameMap,
            FAOFoodPriceService.FoodPriceIndex fao, List<FAOFoodPriceService.FoodPriceIndex> trend,
            String priceField) {

        CommoditySignal signal = new CommoditySignal();
        signal.setCommodity(commodity);
        signal.setTopContributors(new ArrayList<>());

        // Current price
        double currentPrice = getPriceValue(fao, priceField);
        signal.setCurrentPrice(currentPrice);

        // Historical prices (6m and 12m ago)
        if (trend != null && trend.size() >= 12) {
            double price6m = getPriceValue(trend.get(trend.size() - 7), priceField);
            double price12m = getPriceValue(trend.get(trend.size() - 13), priceField);
            signal.setPrice6mAgo(price6m);
            signal.setPrice12mAgo(price12m);
            signal.setPriceTrend6m(price6m > 0 ? ((currentPrice - price6m) / price6m) * 100 : 0);
        }

        // Calculate DPI from importers
        double dpi = 0;
        for (var entry : importers.entrySet()) {
            String iso3 = entry.getKey();
            double importVol = entry.getValue();
            double change = changeMap.getOrDefault(iso3, 0.0);

            if (change > 0.5) { // Only count meaningful worsening
                double contribution = change * importVol;
                dpi += contribution;

                CountryContribution cc = new CountryContribution();
                cc.setCountryName(nameMap.getOrDefault(iso3, iso3));
                cc.setIso3(iso3);
                cc.setPredictedChange(change);
                cc.setImportVolume(importVol);
                cc.setContribution(contribution);
                cc.setType("IMPORTER");
                signal.getTopContributors().add(cc);
            }
        }

        // Check exporters (supply-side risk)
        StringBuilder exporterRisk = new StringBuilder();
        for (String iso3 : exporters) {
            double change = changeMap.getOrDefault(iso3, 0.0);
            if (change > 2) {
                exporterRisk.append(nameMap.getOrDefault(iso3, iso3))
                    .append(": food insecurity worsening (").append(String.format("%+.1fpp", change))
                    .append(") — potential supply disruption from major exporter. ");

                // Add as negative contributor (supply risk amplifies demand signal)
                CountryContribution cc = new CountryContribution();
                cc.setCountryName(nameMap.getOrDefault(iso3, iso3));
                cc.setIso3(iso3);
                cc.setPredictedChange(change);
                cc.setImportVolume(0);
                cc.setContribution(change * 5); // Weight exporter disruption heavily
                cc.setType("EXPORTER_RISK");
                signal.getTopContributors().add(cc);
                dpi += change * 5; // Amplify signal
            }
        }
        signal.setExporterRisk(exporterRisk.length() > 0 ? exporterRisk.toString().trim() : null);

        // Sort contributors by contribution
        signal.getTopContributors().sort((a, b) -> Double.compare(b.getContribution(), a.getContribution()));
        if (signal.getTopContributors().size() > 6) {
            signal.setTopContributors(signal.getTopContributors().subList(0, 6));
        }

        signal.setDemandPressureIndex(Math.round(dpi * 10.0) / 10.0);

        // Signal strength
        if (dpi > 20) signal.setSignalStrength("STRONG");
        else if (dpi > 10) signal.setSignalStrength("MODERATE");
        else if (dpi > 5) signal.setSignalStrength("WEAK");
        else signal.setSignalStrength("NONE");

        // Price direction
        if (signal.getPriceTrend6m() > 3) signal.setDirection("UPWARD");
        else if (signal.getPriceTrend6m() < -3) signal.setDirection("DOWNWARD");
        else signal.setDirection("STABLE");

        // Interpretation
        signal.setInterpretation(buildInterpretation(signal));

        return signal;
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
