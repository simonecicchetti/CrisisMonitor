package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Country {
    private Integer id;
    private String name;
    private String iso3;
    private String iso2;
    private Long population;
    private String incomeLevel;

    // Geographic coordinates for map
    private Double latitude;
    private Double longitude;

    // Severity tier (1=critical, 2=serious, 3=moderate, 4=low, null=no data)
    private Integer severityTier;

    // IPC Data
    private Long peoplePhase3to5;
    private Double percentPhase3to5;
    private Long peoplePhase4to5;
    private Double percentPhase4to5;
    private String ipcAnalysisPeriod;

    // Computed alert level for map coloring
    private String alertLevel; // CRITICAL, HIGH, MEDIUM, LOW, NO_DATA
}
