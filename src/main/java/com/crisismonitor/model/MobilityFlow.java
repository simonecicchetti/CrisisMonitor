package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents mobility flow data (origin → destination movements)
 * Sources: UNHCR Asylum Applications, Migration flows
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MobilityFlow {
    private String originIso3;
    private String originName;
    private String destinationIso3;
    private String destinationName;
    private Integer year;
    private Integer month;          // Optional for monthly data

    private Long count;             // Number of people
    private String flowType;        // ASYLUM_APPLICATION, REFUGEE_MOVEMENT, etc.
    private String procedureType;   // G (Government), U (UNHCR)
    private String appType;         // N (New), R (Repeat)

    private String source;          // UNHCR, EUROSTAT, etc.

    // For visualization
    private Double originLat;
    private Double originLon;
    private Double destLat;
    private Double destLon;
}
