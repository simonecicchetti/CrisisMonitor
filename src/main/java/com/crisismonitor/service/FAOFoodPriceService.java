package com.crisismonitor.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;

/**
 * FAO Food Price Index (FFPI) — global monthly food price indicators.
 *
 * Source: https://www.fao.org/worldfoodsituation/foodpricesindex/en/
 * Public CSV, updated monthly, no auth required.
 *
 * Provides: Food Price Index, Meat, Dairy, Cereals, Oils, Sugar
 * Base: 2014-2016 = 100
 *
 * Used in: Intelligence report context, overview dashboard, food crisis analysis.
 * NOT used in per-country risk score (global index, not country-specific).
 */
@Slf4j
@Service
public class FAOFoodPriceService {

    private static final String CSV_URL =
        "https://www.fao.org/media/docs/worldfoodsituationlibraries/default-document-library/" +
        "food_price_indices_data_csv_mar.csv?sfvrsn=523ebd2a_72&download=true";

    @Data
    public static class FoodPriceIndex {
        private String date;           // "2026-02"
        private double foodIndex;      // Overall food price index
        private double meatIndex;
        private double dairyIndex;
        private double cerealsIndex;
        private double oilsIndex;
        private double sugarIndex;

        // Computed: year-on-year change
        private Double foodYoY;
        private Double cerealsYoY;
    }

    /**
     * Get the complete FAO Food Price Index time series.
     * Cached for 24 hours (data updates monthly).
     */
    @Cacheable("faoFoodPriceIndex")
    public List<FoodPriceIndex> getAll() {
        log.info("Fetching FAO Food Price Index...");
        try {
            URL url = new URL(CSV_URL);
            BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));

            List<FoodPriceIndex> results = new ArrayList<>();
            String line;
            int lineNum = 0;

            while ((line = reader.readLine()) != null) {
                lineNum++;
                // Skip header rows (first 3 lines)
                if (lineNum <= 3) continue;

                String[] parts = line.split(",", -1);
                if (parts.length < 7 || parts[0].isBlank()) continue;

                try {
                    FoodPriceIndex fpi = new FoodPriceIndex();
                    fpi.setDate(parts[0].trim());
                    fpi.setFoodIndex(parseDouble(parts[1]));
                    fpi.setMeatIndex(parseDouble(parts[2]));
                    fpi.setDairyIndex(parseDouble(parts[3]));
                    fpi.setCerealsIndex(parseDouble(parts[4]));
                    fpi.setOilsIndex(parseDouble(parts[5]));
                    fpi.setSugarIndex(parseDouble(parts[6]));
                    results.add(fpi);
                } catch (Exception e) {
                    // Skip malformed rows
                }
            }
            reader.close();

            // Compute year-on-year changes
            for (int i = 12; i < results.size(); i++) {
                FoodPriceIndex current = results.get(i);
                FoodPriceIndex yearAgo = results.get(i - 12);
                if (yearAgo.getFoodIndex() > 0) {
                    current.setFoodYoY(((current.getFoodIndex() - yearAgo.getFoodIndex()) / yearAgo.getFoodIndex()) * 100);
                }
                if (yearAgo.getCerealsIndex() > 0) {
                    current.setCerealsYoY(((current.getCerealsIndex() - yearAgo.getCerealsIndex()) / yearAgo.getCerealsIndex()) * 100);
                }
            }

            log.info("FAO FFPI loaded: {} monthly records, latest: {}", results.size(),
                results.isEmpty() ? "none" : results.get(results.size() - 1).getDate());
            return results;

        } catch (Exception e) {
            log.warn("Failed to fetch FAO FFPI: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get the latest FAO Food Price Index values.
     */
    public FoodPriceIndex getLatest() {
        List<FoodPriceIndex> all = getAll();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /**
     * Get last N months of data (for trend display).
     */
    public List<FoodPriceIndex> getRecent(int months) {
        List<FoodPriceIndex> all = getAll();
        int start = Math.max(0, all.size() - months);
        return all.subList(start, all.size());
    }

    private double parseDouble(String s) {
        if (s == null || s.isBlank()) return 0;
        return Double.parseDouble(s.trim().replace(",", ""));
    }
}
