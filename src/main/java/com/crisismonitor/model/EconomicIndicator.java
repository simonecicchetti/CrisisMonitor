package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EconomicIndicator {
    private String iso3;
    private String countryName;
    private String indicatorCode;
    private String indicatorName;
    private Integer year;
    private Double value;

    // Alert thresholds for inflation
    public static String computeInflationAlert(Double inflation) {
        if (inflation == null) return "NO_DATA";
        if (inflation >= 25) return "CRITICAL";   // Hyperinflation
        if (inflation >= 15) return "HIGH";       // High inflation
        if (inflation >= 10) return "MODERATE";   // Moderate concern
        return "NORMAL";
    }
}
