package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Aggregated country profile — single response for country detail modal.
 * Combines data from RiskScore, IPC, Climate, Conflict, Economy, Displacement, ReliefWeb.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CountryProfileData {

    // Identity
    private String iso3;
    private String iso2;
    private String name;
    private String region;

    // === COMPOSITE RISK SCORE ===
    private Integer score;             // 0-100
    private String riskLevel;          // CRITICAL, ALERT, WARNING, WATCH, STABLE
    private Integer climateScore;      // 0-100
    private Integer conflictScore;     // 0-100
    private Integer economicScore;     // 0-100
    private Integer foodSecurityScore; // 0-100
    private List<String> drivers;      // Top contributing drivers
    private Double confidence;         // 0.0-1.0
    private String confidenceNote;

    // AI scoring reasons (from Qwen)
    private String foodReason;
    private String conflictReason;
    private String climateReason;
    private String economicReason;
    private String summary;
    private String scoreSource;

    // Trend
    private String trend;              // rising, falling, stable, new
    private String trendIcon;
    private Integer scoreDelta;        // vs 7 days ago
    private String persistenceLabel;   // PERSISTENT, RAPID_DETERIORATION, etc.
    private Integer persistenceDays;

    // Horizon
    private String horizon;
    private String horizonReason;

    // === FOOD SECURITY ===
    private Double ipcPhase;           // 1.0-5.0
    private String ipcDescription;     // "Crisis", "Emergency", etc.
    private Long peoplePhase3to5;
    private Double percentPhase3to5;
    private Long peoplePhase4to5;
    private Double percentPhase4to5;
    private Double fcsPrevalence;      // Food Consumption Score prevalence
    private Long fcsPeople;
    private Double rcsiPrevalence;     // Reduced Coping Strategy Index
    private Long rcsiPeople;

    // === CLIMATE ===
    private Double precipAnomaly;      // % deviation
    private String precipCategory;     // DROUGHT, NORMAL, FLOODING_RISK
    private Integer precipRiskScore;

    // === CONFLICT ===
    private Double gdeltZScore;
    private String spikeLevel;         // CRITICAL, HIGH, ELEVATED, NORMAL
    private Integer articles7d;
    private List<HeadlineItem> headlines;

    // === ECONOMY ===
    private Double currencyChange30d;  // % change
    private String currencyCode;
    private Double inflationRate;
    private String inflationYear;

    // === DISPLACEMENT ===
    private Long idps;                 // Internal displacement (IOM DTM)
    private Long refugees;             // Refugees originated from (UNHCR)

    // === RECENT REPORTS (ReliefWeb) ===
    private List<ReportItem> recentReports;

    // === ACTIVE SITUATIONS (Claude-detected) ===
    private List<SituationItem> situations;

    // === TREND HISTORY (sparkline data) ===
    private List<TrendPoint> trendHistory;        // Daily score snapshots

    // === WHO DISEASE OUTBREAKS ===
    private List<DiseaseOutbreakItem> diseaseOutbreaks;

    // === DATA FRESHNESS ===
    private Map<String, String> dataFreshness;    // sourceId → "FRESH"/"STALE"/"UNAVAILABLE"

    // Inner DTOs for nested data
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HeadlineItem {
        private String title;
        private String url;
        private String source;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportItem {
        private String title;
        private String url;
        private String source;
        private String date;
        private String format;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SituationItem {
        private String type;
        private String severity;
        private String summary;
        private Integer articleCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendPoint {
        private String date;       // ISO date string
        private Integer score;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiseaseOutbreakItem {
        private String disease;
        private String title;
        private String date;
        private String timeAgo;
        private String url;
    }
}
