package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Represents a displacement event (near real-time displacement incidents)
 * Sources: IDMC IDU, IOM DTM events
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisplacementEvent {
    private String id;
    private String iso3;
    private String countryName;
    private String adminLevel;
    private String adminName;

    private Double latitude;
    private Double longitude;

    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate reportingDate;

    private Long figure;            // Number of people displaced
    private String eventType;       // CONFLICT, DISASTER, DEVELOPMENT
    private String disasterType;    // FLOOD, EARTHQUAKE, etc. (if disaster)
    private String eventName;

    private boolean provisional;    // Data may be revised
    private String source;          // IDMC, DTM, etc.

    public String getSeverityLevel() {
        if (figure == null) return "UNKNOWN";
        if (figure > 100000) return "CRITICAL";
        if (figure > 10000) return "HIGH";
        if (figure > 1000) return "MEDIUM";
        return "LOW";
    }
}
