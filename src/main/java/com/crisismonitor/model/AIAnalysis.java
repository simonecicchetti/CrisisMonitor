package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI-generated crisis analysis response.
 * Contains structured insights from Claude analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIAnalysis {

    private String scope;           // "global", "country", "region", "custom"
    private String countryIso3;     // For country-specific analysis
    private String countryName;     // For country-specific analysis
    private String region;          // For regional analysis (Africa, LAC, MENA, Asia, Europe)
    private String summary;         // Brief narrative summary

    // Structured analysis output
    private List<String> keyFindings;    // 5 key findings
    private List<String> drivers;        // 3 primary drivers
    private List<String> watchList;      // 3 items to watch next 7 days

    // News Signal (GDELT media coverage analysis)
    private NewsSignal newsSignal;

    // For custom questions
    private String customQuestion;
    private String customAnswer;

    // Metadata
    private LocalDateTime generatedAt;
    private String dataVersion;          // Hash of input data for cache key
    private String model;                // "claude-3-haiku" or "claude-3-sonnet"
    private boolean fromCache;
}
