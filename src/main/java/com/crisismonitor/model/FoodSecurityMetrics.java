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
public class FoodSecurityMetrics {
    private String iso3;
    private String name;
    private LocalDate date;
    private String dataType;          // PREDICTION or SURVEY

    // Food Consumption Score
    private Long fcsPeople;
    private Double fcsPrevalence;

    // Reduced Coping Strategy Index
    private Long rcsiPeople;
    private Double rcsiPrevalence;

    // Additional metrics (when available)
    private Long healthAccessPeople;
    private Double healthAccessPrevalence;
    private Long marketAccessPeople;
    private Double marketAccessPrevalence;
    private Long livelihoodCopingPeople;
    private Double livelihoodCopingPrevalence;
}
