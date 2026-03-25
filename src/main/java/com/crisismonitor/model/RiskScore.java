package com.crisismonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Predictive Risk Score - Combines multiple leading indicators
 * to forecast crisis probability within 30/60/90 days.
 *
 * Based on 2-of-3 confirmation rule:
 * Warning triggers only if ≥2 indicators are elevated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskScore {

    private String iso3;
    private String iso2;
    private String countryName;

    // Overall score (0-100)
    private int score;
    private String riskLevel;  // STABLE, WATCH, WARNING, ALERT, CRITICAL

    // Individual indicator scores (0-100 each)
    private int climateScore;      // Precipitation anomaly
    private int conflictScore;     // GDELT spike
    private int economicScore;     // Currency + prices
    private int foodSecurityScore; // IPC phase

    // Which indicators are elevated (for 2-of-3 rule)
    private boolean climateElevated;
    private boolean conflictElevated;
    private boolean economicElevated;
    private int elevatedCount;

    // Primary drivers (sorted by contribution)
    private List<String> drivers;

    // Time horizon prediction
    private String horizon;        // "30 days", "60 days", "90 days"
    private String horizonReason;  // Why this horizon

    // Confidence level based on data freshness
    private double confidence;     // 0.0 - 1.0
    private String confidenceNote;

    // Raw data points for transparency
    private Double precipitationAnomaly;  // % deviation from normal
    private Double currencyChange30d;     // % change in 30 days
    private Double gdeltZScore;           // Conflict spike z-score
    private Double ipcPhase;              // Current IPC phase

    private LocalDateTime calculatedAt;

    // ===== TREND INDICATORS (vs 7 days ago) =====
    private Integer previousScore;        // Score 7 days ago (null if no history)
    private Integer scoreDelta;           // Change: current - previous
    private String trend;                 // "rising", "falling", "stable", "new"
    private String trendIcon;             // "↑", "↓", "→", "●"

    // ===== NOWCAST ML =====
    private Double nowcastPrediction;     // Predicted 90d % change
    private Integer nowcastAmplifier;     // How much it boosted food score

    // ===== AI SCORING (Qwen) =====
    private String foodReason;        // Why this food score
    private String conflictReason;    // Why this conflict score
    private String climateReason;     // Why this climate score
    private String economicReason;    // Why this economic score
    private String summary;           // AI overall assessment
    private String scoreSource;       // "qwen" or "formula"
    private String qwenGeneratedAt;   // When Qwen score was generated

    // ===== PERSISTENCE INDICATORS =====
    private String persistenceLabel;      // PERSISTENT, RAPID_DETERIORATION, IMPROVING, STABLE, NEW
    private String persistenceIcon;       // ◆, ⬆, ⬇, →, ●
    private Integer persistenceStreak;    // Consecutive refreshes at same/worse level
    private Integer persistenceDays;      // Approx days in high-risk state (if persistent)

    /**
     * Get CSS class for risk level badge
     */
    @JsonIgnore
    public String getRiskClass() {
        if (score >= 86) return "critical";
        if (score >= 71) return "alert";
        if (score >= 51) return "warning";
        if (score >= 31) return "watch";
        return "stable";
    }

    /**
     * Is this a high-risk country requiring immediate attention?
     */
    @JsonIgnore
    public boolean isHighRisk() {
        return score >= 60;
    }

    /**
     * Does this have multiple elevated indicators?
     */
    @JsonIgnore
    public boolean isConfirmed() {
        return elevatedCount >= 2;
    }
}
