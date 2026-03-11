package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Tracks data freshness for each data source.
 * Used to show transparency about when data was last updated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceStatus {

    private String sourceId;           // e.g., "ipc", "inflation", "idps", "ndvi"
    private String sourceName;         // e.g., "IPC Phase (FEWS NET)"
    private LocalDateTime lastUpdated; // When data was last fetched
    private LocalDateTime dataDate;    // Date of the actual data (may differ from fetch time)
    private String status;             // "fresh", "recent", "stale", "unavailable"
    private String statusIcon;         // "●", "○", "◐", "✕"
    private int recordCount;           // Number of records
    private String note;               // Optional note about data quality

    /**
     * Calculate freshness status based on age thresholds.
     * Fresh: < 24 hours
     * Recent: < 7 days
     * Stale: >= 7 days
     */
    public static DataSourceStatus create(String id, String name, LocalDateTime updated,
                                          LocalDateTime dataDate, int count) {
        String status;
        String icon;

        if (updated == null) {
            status = "unavailable";
            icon = "✕";
        } else {
            long hoursOld = ChronoUnit.HOURS.between(updated, LocalDateTime.now());
            if (hoursOld < 24) {
                status = "fresh";
                icon = "●";
            } else if (hoursOld < 168) { // 7 days
                status = "recent";
                icon = "◐";
            } else {
                status = "stale";
                icon = "○";
            }
        }

        return DataSourceStatus.builder()
                .sourceId(id)
                .sourceName(name)
                .lastUpdated(updated)
                .dataDate(dataDate)
                .status(status)
                .statusIcon(icon)
                .recordCount(count)
                .build();
    }

    /**
     * Summary of all data sources for the dashboard.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataFreshnessSummary {
        private List<DataSourceStatus> sources;
        private int totalSources;
        private int freshCount;
        private int recentCount;
        private int staleCount;
        private int unavailableCount;
        private String overallStatus;    // "good", "partial", "degraded"
        private LocalDateTime generatedAt;

        public static DataFreshnessSummary from(List<DataSourceStatus> sources) {
            int fresh = 0, recent = 0, stale = 0, unavailable = 0;

            for (DataSourceStatus s : sources) {
                switch (s.getStatus()) {
                    case "fresh" -> fresh++;
                    case "recent" -> recent++;
                    case "stale" -> stale++;
                    case "unavailable" -> unavailable++;
                }
            }

            String overall;
            if (unavailable > 0 || stale > sources.size() / 2) {
                overall = "degraded";
            } else if (stale > 0 || unavailable > 0) {
                overall = "partial";
            } else {
                overall = "good";
            }

            return DataFreshnessSummary.builder()
                    .sources(sources)
                    .totalSources(sources.size())
                    .freshCount(fresh)
                    .recentCount(recent)
                    .staleCount(stale)
                    .unavailableCount(unavailable)
                    .overallStatus(overall)
                    .generatedAt(LocalDateTime.now())
                    .build();
        }
    }
}
