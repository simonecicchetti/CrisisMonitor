package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Conflict Event - represents a conflict-related news report or validated event
 *
 * Sources:
 * - GDELT: Media reports (high volume, unvalidated)
 * - ACLED: Validated events with fatalities (when available)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConflictEvent {

    private String id;
    private String iso3;
    private String countryName;

    // Location
    private String adminLevel;
    private String adminName;
    private Double latitude;
    private Double longitude;

    // Event details
    private String title;
    private String eventType;  // BATTLE, VIOLENCE_AGAINST_CIVILIANS, PROTEST, RIOT, MEDIA_REPORT
    private String subEventType;
    private LocalDate eventDate;

    // Impact (primarily from ACLED when available)
    private Integer fatalities;
    private String actor1;
    private String actor2;

    // Sentiment/Tone (from GDELT)
    private Double tone; // -100 to +100

    // Source tracking
    private String source; // GDELT, ACLED
    private String url;
    private Boolean validated; // true = ACLED, false = GDELT media report

    /**
     * Get severity based on available data
     */
    public String getSeverity() {
        // If we have fatalities data (ACLED)
        if (fatalities != null) {
            if (fatalities >= 100) return "CRITICAL";
            if (fatalities >= 25) return "HIGH";
            if (fatalities >= 5) return "MEDIUM";
            return "LOW";
        }

        // If we only have tone (GDELT)
        if (tone != null) {
            if (tone < -5) return "HIGH";  // Very negative coverage
            if (tone < -2) return "MEDIUM";
            return "LOW";
        }

        return "UNKNOWN";
    }

    /**
     * Is this a validated event (vs media report)?
     */
    public boolean isValidated() {
        return Boolean.TRUE.equals(validated) || "ACLED".equals(source);
    }
}
