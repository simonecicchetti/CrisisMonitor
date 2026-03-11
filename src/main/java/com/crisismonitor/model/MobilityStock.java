package com.crisismonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents displacement/mobility stock data (point-in-time estimates)
 * Sources: UNHCR, IOM DTM
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MobilityStock {
    private String iso3;
    private String countryName;
    private Integer year;

    // UNHCR categories
    private Long refugees;          // Refugees from this country
    private Long asylumSeekers;     // Pending asylum cases
    private Long idps;              // Internally displaced persons
    private Long returnedRefugees;  // Returned refugees
    private Long returnedIdps;      // Returned IDPs
    private Long stateless;         // Stateless persons
    private Long otherConcern;      // Others of concern

    // Hosting data (when country is destination)
    private Long refugeesHosted;    // Refugees hosted in this country

    // Computed
    private Long totalDisplaced;    // refugees + idps + asylumSeekers

    private String source;          // UNHCR, DTM, etc.
    private String adminLevel;      // ADMIN0, ADMIN1, ADMIN2

    public void computeTotalDisplaced() {
        long total = 0;
        if (refugees != null) total += refugees;
        if (idps != null) total += idps;
        if (asylumSeekers != null) total += asylumSeekers;
        this.totalDisplaced = total;
    }

    @JsonIgnore
    public String getSeverityLevel() {
        if (totalDisplaced == null) computeTotalDisplaced();
        if (totalDisplaced > 5000000) return "CRITICAL";
        if (totalDisplaced > 1000000) return "HIGH";
        if (totalDisplaced > 100000) return "MEDIUM";
        return "LOW";
    }
}
