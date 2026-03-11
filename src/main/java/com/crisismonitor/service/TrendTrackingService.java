package com.crisismonitor.service;

import com.crisismonitor.model.RiskScore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks historical risk scores to calculate trends and persistence.
 * Stores daily snapshots and computes 7-day deltas.
 *
 * Persistence tracking:
 * - PERSISTENT: same or worse level for 3+ consecutive refreshes
 * - RAPID_DETERIORATION: level increased in last 1-2 refreshes
 * - IMPROVING: level decreased for 2+ refreshes
 *
 * In production, this would use a database. For the hackathon,
 * we use an in-memory store that persists for the session.
 */
@Slf4j
@Service
public class TrendTrackingService {

    // Map: iso3 -> (date -> score)
    private final Map<String, Map<LocalDate, Integer>> scoreHistory = new ConcurrentHashMap<>();

    // Map: iso3 -> (date -> riskLevel) for persistence tracking
    private final Map<String, Map<LocalDate, String>> levelHistory = new ConcurrentHashMap<>();

    // Map: iso3 -> streak count (consecutive days at same or worse level)
    private final Map<String, Integer> persistenceStreak = new ConcurrentHashMap<>();

    // Map: iso3 -> last level for comparison
    private final Map<String, String> lastKnownLevel = new ConcurrentHashMap<>();

    // How many days back to look for comparison
    private static final int COMPARISON_DAYS = 7;

    // Threshold for "stable" (no significant change)
    private static final int STABLE_THRESHOLD = 3;

    // Persistence thresholds
    private static final int PERSISTENT_THRESHOLD = 3;  // 3+ refreshes at same/worse level

    // Risk level hierarchy for comparison
    private static final Map<String, Integer> LEVEL_RANK = Map.of(
            "STABLE", 1,
            "WATCH", 2,
            "WARNING", 3,
            "ALERT", 4,
            "CRITICAL", 5
    );

    /**
     * Record today's score for a country.
     */
    public void recordScore(String iso3, int score) {
        LocalDate today = LocalDate.now();

        scoreHistory
                .computeIfAbsent(iso3, k -> new ConcurrentHashMap<>())
                .put(today, score);

        // Clean up old entries (keep last 30 days)
        cleanupOldEntries(iso3);
    }

    /**
     * Enrich a RiskScore with trend and persistence information.
     */
    public void enrichWithTrend(RiskScore riskScore) {
        if (riskScore == null || riskScore.getIso3() == null) return;

        String iso3 = riskScore.getIso3();
        int currentScore = riskScore.getScore();
        String currentLevel = riskScore.getRiskLevel();

        // Record current score
        recordScore(iso3, currentScore);

        // Get historical score
        Integer previousScore = getScoreFromDaysAgo(iso3, COMPARISON_DAYS);

        if (previousScore == null) {
            // No history - this is new
            riskScore.setTrend("new");
            riskScore.setTrendIcon("●");
            riskScore.setPreviousScore(null);
            riskScore.setScoreDelta(null);
        } else {
            int delta = currentScore - previousScore;
            riskScore.setPreviousScore(previousScore);
            riskScore.setScoreDelta(delta);

            if (Math.abs(delta) <= STABLE_THRESHOLD) {
                riskScore.setTrend("stable");
                riskScore.setTrendIcon("→");
            } else if (delta > 0) {
                riskScore.setTrend("rising");
                riskScore.setTrendIcon("↑");
            } else {
                riskScore.setTrend("falling");
                riskScore.setTrendIcon("↓");
            }
        }

        // === PERSISTENCE TRACKING ===
        updatePersistence(iso3, currentLevel, riskScore);

        log.debug("Trend for {}: {} ({} -> {}, delta: {}, persistence: {})",
                iso3,
                riskScore.getTrend(),
                previousScore,
                currentScore,
                riskScore.getScoreDelta(),
                riskScore.getPersistenceLabel());
    }

    /**
     * Update persistence tracking and set labels.
     */
    private void updatePersistence(String iso3, String currentLevel, RiskScore riskScore) {
        String previousLevel = lastKnownLevel.get(iso3);
        int currentRank = LEVEL_RANK.getOrDefault(currentLevel, 0);
        int previousRank = previousLevel != null ? LEVEL_RANK.getOrDefault(previousLevel, 0) : 0;

        // Record level history
        levelHistory
                .computeIfAbsent(iso3, k -> new ConcurrentHashMap<>())
                .put(LocalDate.now(), currentLevel);

        int streak = persistenceStreak.getOrDefault(iso3, 0);

        if (previousLevel == null) {
            // First time seeing this country
            streak = 1;
            riskScore.setPersistenceLabel("NEW");
            riskScore.setPersistenceIcon("●");
        } else if (currentRank >= previousRank) {
            // Same or worse level - increase streak
            streak++;
            if (currentRank > previousRank) {
                // Deteriorated
                riskScore.setPersistenceLabel("RAPID_DETERIORATION");
                riskScore.setPersistenceIcon("⬆");
            } else if (streak >= PERSISTENT_THRESHOLD) {
                // Persistent at same level
                riskScore.setPersistenceLabel("PERSISTENT");
                riskScore.setPersistenceIcon("◆");
            } else {
                riskScore.setPersistenceLabel("STABLE");
                riskScore.setPersistenceIcon("→");
            }
        } else {
            // Improved - reset streak
            streak = 1;
            riskScore.setPersistenceLabel("IMPROVING");
            riskScore.setPersistenceIcon("⬇");
        }

        // Store updated values
        persistenceStreak.put(iso3, streak);
        lastKnownLevel.put(iso3, currentLevel);
        riskScore.setPersistenceStreak(streak);

        // Set persistence days estimate (streak * refresh interval, assume ~1 day)
        if (streak >= PERSISTENT_THRESHOLD && isHighRiskLevel(currentLevel)) {
            riskScore.setPersistenceDays(streak);
        }
    }

    private boolean isHighRiskLevel(String level) {
        return "CRITICAL".equals(level) || "ALERT".equals(level) || "WARNING".equals(level);
    }

    /**
     * Get the score from N days ago.
     */
    private Integer getScoreFromDaysAgo(String iso3, int daysAgo) {
        Map<LocalDate, Integer> history = scoreHistory.get(iso3);
        if (history == null || history.isEmpty()) {
            return null;
        }

        LocalDate targetDate = LocalDate.now().minusDays(daysAgo);

        // Look for exact match or closest earlier date
        for (int i = 0; i <= 3; i++) { // Allow 3 days tolerance
            LocalDate checkDate = targetDate.minusDays(i);
            if (history.containsKey(checkDate)) {
                return history.get(checkDate);
            }
        }

        return null;
    }

    /**
     * Clean up entries older than 30 days.
     */
    private void cleanupOldEntries(String iso3) {
        Map<LocalDate, Integer> history = scoreHistory.get(iso3);
        if (history == null) return;

        LocalDate cutoff = LocalDate.now().minusDays(30);
        history.keySet().removeIf(date -> date.isBefore(cutoff));
    }

    /**
     * Get the score history for a country as a sorted list of date→score entries.
     * Returns the last 30 days of data (or whatever is available).
     */
    public List<Map.Entry<LocalDate, Integer>> getScoreHistory(String iso3) {
        Map<LocalDate, Integer> history = scoreHistory.get(iso3);
        if (history == null || history.isEmpty()) {
            return Collections.emptyList();
        }

        return history.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
    }

    /**
     * Get trend summary across all countries.
     */
    public Map<String, Integer> getTrendSummary() {
        Map<String, Integer> summary = new HashMap<>();
        summary.put("rising", 0);
        summary.put("falling", 0);
        summary.put("stable", 0);
        summary.put("new", 0);

        // This would be called after enriching all scores
        // For now, return empty summary
        return summary;
    }

    /**
     * Seed initial history with simulated past data.
     * Called on startup to have meaningful trends immediately.
     */
    public void seedHistoryFromCurrent(List<RiskScore> currentScores) {
        LocalDate today = LocalDate.now();

        for (RiskScore score : currentScores) {
            if (score == null || score.getIso3() == null) continue;

            String iso3 = score.getIso3();
            int currentScore = score.getScore();

            // Create synthetic history with slight variations
            // This simulates having tracked data for 7 days
            Map<LocalDate, Integer> history = scoreHistory
                    .computeIfAbsent(iso3, k -> new ConcurrentHashMap<>());

            // Generate history for past 7 days with small random variations
            Random rand = new Random(iso3.hashCode()); // Deterministic per country

            for (int daysAgo = 7; daysAgo >= 1; daysAgo--) {
                LocalDate date = today.minusDays(daysAgo);
                // Vary by ±5 points max, trending toward current
                int variation = rand.nextInt(11) - 5; // -5 to +5
                int historicalScore = Math.max(0, Math.min(100, currentScore + variation));
                history.put(date, historicalScore);
            }

            // Today's score is exact
            history.put(today, currentScore);
        }

        log.info("Seeded trend history for {} countries", currentScores.size());
    }
}
