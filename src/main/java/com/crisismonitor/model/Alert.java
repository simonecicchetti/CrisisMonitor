package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Alert {
    private String countryName;
    private String iso3;
    private String type;        // e.g., "FCS_DETERIORATION_HIGH", "CONFLICT_FATALITIES_INCIDENCE_HIGH"
    private String category;    // FOOD_SECURITY, CLIMATE, CONFLICT, COVID
    private Double value;

    // For FCS alerts
    private Double fcsChange;
    private Double fcsPrevalence;

    // Computed severity for display
    private String severity; // CRITICAL, HIGH, MEDIUM
}
