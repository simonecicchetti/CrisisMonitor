package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Precipitation anomaly data from Open-Meteo.
 * Negative anomaly (drought) is a key predictor for food security crises.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrecipitationAnomaly {

    private String iso2;
    private String iso3;
    private String countryName;

    // Location (capital or agricultural center)
    private double latitude;
    private double longitude;
    private String locationName;

    // Precipitation data
    private double currentPrecipMm;        // Current period (30 days)
    private double normalPrecipMm;         // Historical average for same period
    private double anomalyPercent;         // % deviation from normal (-100 to +∞)
    private double anomalyMm;              // Absolute deviation in mm

    // ET0 (evapotranspiration) - indicator of water stress
    private Double et0Current;
    private Double et0Normal;
    private Double et0AnomalyPercent;

    // Risk assessment
    private String riskLevel;              // NORMAL, DRY, DROUGHT, SEVERE_DROUGHT
    private int riskScore;                 // 0-100

    // Classification
    private String spiCategory;            // SPI-like category
    private boolean drought;
    private boolean severeDrought;

    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate calculatedAt;

    /**
     * Classify based on standardized anomaly thresholds
     * Similar to SPI (Standardized Precipitation Index)
     */
    public static String classifyAnomaly(double anomalyPercent) {
        if (anomalyPercent <= -80) return "EXCEPTIONAL_DROUGHT";
        if (anomalyPercent <= -60) return "EXTREME_DROUGHT";
        if (anomalyPercent <= -40) return "SEVERE_DROUGHT";
        if (anomalyPercent <= -20) return "MODERATE_DROUGHT";
        if (anomalyPercent <= -10) return "ABNORMALLY_DRY";
        if (anomalyPercent >= 50) return "FLOODING_RISK";
        if (anomalyPercent >= 30) return "ABNORMALLY_WET";
        return "NORMAL";
    }

    /**
     * Calculate risk score from anomaly.
     *
     * Outlier protection: anomalies beyond -90% or +500% are capped.
     * -100% means ZERO rainfall for 90 days — while possible in deserts,
     * it's usually an API error (offline weather station, missing data).
     * +1600% is physically implausible. Real extreme floods are +200-400%.
     * Cap at 70 for outlier-range values to prevent false CRITICAL scores.
     */
    public static int calculateRiskScore(double anomalyPercent) {
        if (anomalyPercent >= 0) {
            // Excess rainfall: moderate risk for flooding
            // Cap at 50: excess rain is less immediately dangerous than drought
            // for food security (crops can often recover from flooding)
            if (anomalyPercent > 500) return 50; // outlier: cap at 50
            return Math.min(50, (int)(anomalyPercent / 2));
        }
        // Drought: higher risk for food security
        double absAnomaly = Math.abs(anomalyPercent);

        // Outlier protection: -95% to -100% is almost certainly API error
        // (weather station offline = 0 readings = -100%). Cap score at 70.
        if (absAnomaly >= 95) return 70;

        if (absAnomaly >= 80) return 85;
        if (absAnomaly >= 60) return 70;
        if (absAnomaly >= 40) return 55;
        if (absAnomaly >= 20) return 40;
        if (absAnomaly >= 10) return 25;
        return 10;
    }
}
