package com.crisismonitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * News headline with source URL for traceability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Headline {
    private String title;
    private String url;
    private String source;  // Domain name (e.g., "reuters.com", "bbc.com")
    private String date;    // Publication date
    private String imageUrl; // Social/preview image URL (og:image, socialimage)
}
