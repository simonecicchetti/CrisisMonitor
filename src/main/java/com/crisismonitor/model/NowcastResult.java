package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NowcastResult {
    private String iso3;
    private String countryName;
    private String region;

    // Current food insecurity proxy (avg of FCS poor% and rCSI crisis%)
    private Double currentProxy;
    private Double fcsPrevalence;
    private Double rcsiPrevalence;

    // Nowcast: predicted % change over 90 days
    private Double predictedChange90d;

    // Projected proxy value (current + predicted change)
    private Double projectedProxy;

    // Confidence
    private String confidence; // HIGH, MEDIUM, LOW
    private String trend;      // WORSENING, IMPROVING, STABLE

    // Historical context
    private Double proxy30dAgo;
    private Double proxy60dAgo;
    private Double proxy90dAgo;
    private Double actualChange30d;
}
