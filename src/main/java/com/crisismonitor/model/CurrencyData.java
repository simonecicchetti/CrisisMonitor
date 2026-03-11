package com.crisismonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Currency exchange rate data for crisis detection.
 * Rapid currency devaluation is a leading indicator of economic crisis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrencyData {

    private String iso2;
    private String countryName;
    private String currencyCode;   // SDG, ETB, YER, etc.
    private String currencyName;

    private Double currentRate;    // Rate vs USD
    private Double rate30dAgo;     // Rate 30 days ago (if available)
    private Double rate90dAgo;     // Rate 90 days ago (if available)

    private Double change30d;      // % change in 30 days
    private Double change90d;      // % change in 90 days

    private String trend;          // STABLE, WEAKENING, DEVALUING, CRISIS
    private int riskScore;         // 0-100 based on devaluation rate

    private LocalDate dataDate;

    /**
     * Calculate risk level based on currency movement
     */
    @JsonIgnore
    public String getRiskLevel() {
        if (change30d == null) return "UNKNOWN";
        double absChange = Math.abs(change30d);
        if (absChange > 30) return "CRISIS";
        if (absChange > 15) return "HIGH";
        if (absChange > 5) return "ELEVATED";
        if (absChange > 2) return "MODERATE";
        return "STABLE";
    }

    /**
     * Is currency experiencing significant devaluation?
     */
    @JsonIgnore
    public boolean isDevaluing() {
        return change30d != null && change30d > 5;
    }
}
