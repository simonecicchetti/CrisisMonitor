package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * A deduped cluster of headlines about the same event/development.
 * This is the core unit for News Feed - NOT raw headlines.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Story {

    private String id;                      // Hash of title + countries + date
    private String title;                   // Representative title (best headline)
    private List<String> topicTags;         // [conflict, migration, food, climate, health, policy]
    private String region;                  // Africa, LAC, MENA, Asia, Europe
    private List<String> countries;         // ISO3 codes
    private List<String> countryNames;      // Display names
    private List<String> sources;           // News sources (dedupe)
    private Instant firstSeen;
    private Instant lastSeen;
    private int volume24h;                  // Article count in last 24h
    private List<Headline> topHeadlines;    // Top 3 actual headlines with URLs (uses existing Headline model)
    private String whyItMatters;            // AI-generated 1-liner (optional)
    private String storyType;               // BREAKING, DEVELOPING, ONGOING, UPDATE

    /**
     * Evidence strength for this story (computed, not serialized)
     */
    @JsonIgnore
    public String getEvidenceLevel() {
        if (volume24h >= 10 && sources.size() >= 3) return "STRONG";
        if (volume24h >= 3 || sources.size() >= 2) return "MODERATE";
        return "WEAK";
    }
}
