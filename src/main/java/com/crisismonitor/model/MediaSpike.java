package com.crisismonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Media Spike Index - measures unusual increase in conflict/crisis coverage
 *
 * Z-Score interpretation:
 * > 3.0  CRITICAL  - Extreme spike, likely major event
 * > 2.0  HIGH      - Significant increase, monitoring required
 * > 1.0  ELEVATED  - Above normal, watch list
 * > 0.5  MODERATE  - Slightly elevated
 * <= 0.5 NORMAL    - Within baseline
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaSpike {

    private String iso3;
    private String countryName;

    // Raw article counts
    private Integer articlesLast7Days;
    private Integer articlesLast30Days;

    // Daily averages
    private Double dailyAvg7d;
    private Double dailyAvg30d;

    // Z-score (standard deviations from baseline)
    @JsonProperty("zscore")
    private Double zScore;

    // Spike classification
    private String spikeLevel; // CRITICAL, HIGH, ELEVATED, MODERATE, NORMAL, ERROR

    // Metadata
    private LocalDate calculatedAt;
    private String source; // GDELT, ACLED (when available)

    // Context: Top headlines explaining the spike
    private List<String> topHeadlines;

    /**
     * Get a human-readable spike description
     */
    @JsonIgnore
    public String getDescription() {
        if (zScore == null) return "Data unavailable";

        if (zScore > 3.0) {
            return String.format("Extreme spike: %.1fx normal coverage", dailyAvg7d / Math.max(dailyAvg30d, 0.1));
        } else if (zScore > 2.0) {
            return String.format("Significant increase: %.1fx normal", dailyAvg7d / Math.max(dailyAvg30d, 0.1));
        } else if (zScore > 1.0) {
            return "Elevated media attention";
        } else if (zScore > 0.5) {
            return "Slightly above normal";
        } else {
            return "Normal coverage levels";
        }
    }

    /**
     * Is this spike actionable (should trigger alert)?
     */
    @JsonIgnore
    public boolean isActionable() {
        return zScore != null && zScore > 2.0;
    }
}
