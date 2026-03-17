package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Individual news item for the two-column News Feed.
 * Used by both ReliefWeb (humanitarian) and Media (GDELT + RSS) columns.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsItem {
    private String title;
    private String url;
    private String source;          // Organization or outlet name (e.g., "OCHA", "BBC", "reuters.com")
    private String sourceType;      // RELIEFWEB, GDELT, RSS
    private String country;         // ISO3 code
    private String countryName;     // Display name
    private String region;          // Africa, LAC, MENA, Asia, Europe
    private List<String> topics;    // [conflict, migration, food, climate, health, humanitarian]
    private String timeAgo;         // "3h ago", "Just now", "1d ago"
    private String format;          // ReliefWeb only: "Situation Report", "Flash Update", etc.
    private String thumbnailUrl;    // Article thumbnail/social image URL (og:image, media:content, etc.)
}
