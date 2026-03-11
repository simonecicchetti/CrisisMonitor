package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClimateData {
    private String iso3;
    private String name;
    private Double ndviAnomaly;      // < 1.0 = drought stress, > 1.0 = normal/wet
    private LocalDate date;
    private String alertLevel;       // SEVERE, MODERATE, NORMAL based on NDVI

    // Computed from NDVI
    public static String computeAlertLevel(Double ndvi) {
        if (ndvi == null) return "NO_DATA";
        if (ndvi < 0.7) return "SEVERE";      // Severe drought
        if (ndvi < 0.9) return "MODERATE";    // Moderate stress
        return "NORMAL";
    }
}
