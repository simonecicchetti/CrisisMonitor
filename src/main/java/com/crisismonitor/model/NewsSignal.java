package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * News Signal - GDELT media coverage analysis for AI Brief.
 *
 * News = trigger, not truth. Used for:
 * - Early hint (something is changing)
 * - Triage (which country to look at now)
 * - Context (explains the "why" behind an alert)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsSignal {

    private String iso3;
    private String countryName;

    // Signal level: CRITICAL, HIGH, MEDIUM, LOW
    private String level;
    private String levelIcon;  // emoji

    // Convergence with risk indicators
    private String convergenceTag;      // "Convergent signal", "Investigate (emerging)", "Underreported", null
    private String convergenceIcon;     // emoji
    private boolean convergent;

    // Spike statistics
    private Integer articlesLast7Days;
    private Double zScore;
    private String spikeStat;  // Formatted: "250 articles / 7d (Z=10.9)"

    // Top headlines with URLs (GDELT - media)
    private List<Headline> headlines;

    // Humanitarian reports with URLs (ReliefWeb - official)
    private List<Headline> humanitarianReports;

    // AI-generated operational insight (one sentence)
    private String operationalInsight;

    // Risk context for convergence calculation
    private String riskLevel;
    private Integer riskScore;

    /**
     * Calculate news level from z-score and article count.
     * CRITICAL: z >= 8 AND 7d >= 150
     * HIGH: z >= 5
     * MEDIUM: z >= 2
     * LOW: z < 2
     */
    public static String calculateLevel(Double zScore, Integer articles) {
        if (zScore == null) return "LOW";

        if (zScore >= 8 && articles != null && articles >= 150) {
            return "CRITICAL";
        } else if (zScore >= 5) {
            return "HIGH";
        } else if (zScore >= 2) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }

    /**
     * Calculate convergence tag based on risk level and news level.
     */
    public static String calculateConvergence(String riskLevel, String newsLevel) {
        boolean riskHigh = "ALERT".equals(riskLevel) || "CRITICAL".equals(riskLevel);
        boolean newsHigh = "HIGH".equals(newsLevel) || "CRITICAL".equals(newsLevel);
        boolean newsCritical = "CRITICAL".equals(newsLevel);
        boolean riskLow = "STABLE".equals(riskLevel) || "WATCH".equals(riskLevel);
        boolean newsLow = "LOW".equals(newsLevel);

        if (riskHigh && newsHigh) {
            return "Convergent signal";
        } else if (riskLow && newsCritical) {
            return "Investigate (emerging)";
        } else if (riskHigh && newsLow) {
            return "Underreported";
        } else {
            return null;  // No special tag
        }
    }

    public static String getLevelIcon(String level) {
        return switch (level) {
            case "CRITICAL" -> "\uD83D\uDD34";  // Red circle
            case "HIGH" -> "\uD83D\uDFE0";      // Orange circle
            case "MEDIUM" -> "\uD83D\uDFE1";    // Yellow circle
            default -> "\u26AA";                // White circle
        };
    }

    public static String getConvergenceIcon(String convergence) {
        if (convergence == null) return null;
        return switch (convergence) {
            case "Convergent signal" -> "\uD83D\uDD34";      // Red
            case "Investigate (emerging)" -> "\uD83D\uDFE1"; // Yellow
            case "Underreported" -> "\uD83D\uDD35";          // Blue
            default -> null;
        };
    }
}
