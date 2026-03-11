package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Comprehensive country profile combining World Bank metadata with crisis data
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CountryProfile {
    // Basic info (from World Bank)
    private String iso3;
    private String iso2;
    private String name;
    private String region;
    private String incomeLevel;
    private String capitalCity;
    private Double latitude;
    private Double longitude;

    // Economic indicators
    private Double inflation;          // Latest annual %
    private Integer inflationYear;
    private Double gdpPerCapita;       // Latest USD
    private Integer gdpYear;
    private Long population;
    private Integer populationYear;
    private Double povertyRate;        // % below $2.15/day
    private Integer povertyYear;

    // Computed alerts
    private String inflationAlert;     // CRITICAL, HIGH, MODERATE, NORMAL
}
