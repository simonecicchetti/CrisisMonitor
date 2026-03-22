package com.crisismonitor.service;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GDACS (Global Disaster Alert and Coordination System) integration.
 * Provides real-time natural disaster alerts: earthquakes, cyclones, floods, droughts, volcanoes.
 *
 * Feed: https://www.gdacs.org/xml/rss.xml
 * Free, no authentication, global coverage.
 * Used to enrich climate scoring and provide disaster context to Qwen.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GDACSService {

    private final WebClient.Builder webClientBuilder;

    private static final String GDACS_RSS = "https://www.gdacs.org/xml/rss.xml";

    @Data
    public static class DisasterAlert {
        private String title;
        private String description;
        private String eventType;   // EQ, TC, FL, DR, VO, WF
        private String alertLevel;  // Green, Orange, Red
        private String severity;
        private String country;     // resolved from coordinates
        private String iso3;
        private double lat;
        private double lon;
        private String date;
        private String link;
    }

    /**
     * Fetch all current GDACS alerts.
     */
    @Cacheable(value = "gdacsAlerts", unless = "#result == null || #result.isEmpty()")
    public List<DisasterAlert> getCurrentAlerts() {
        log.info("Fetching GDACS disaster alerts...");
        try {
            String xml = webClientBuilder.build()
                .get()
                .uri(GDACS_RSS)
                .header("User-Agent", "NotamyNews/1.0 (crisis-monitor)")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(15))
                .block();

            if (xml == null || xml.isBlank()) return Collections.emptyList();

            List<DisasterAlert> alerts = new ArrayList<>();
            // Simple XML parsing — extract items
            String[] items = xml.split("<item>");
            for (int i = 1; i < items.length; i++) {
                String item = items[i];
                DisasterAlert alert = new DisasterAlert();

                alert.setTitle(extractTag(item, "title"));
                alert.setDescription(extractTag(item, "description"));
                alert.setEventType(extractTag(item, "gdacs:eventtype"));
                alert.setAlertLevel(extractTag(item, "gdacs:alertlevel"));
                alert.setSeverity(extractTag(item, "gdacs:severity"));
                alert.setLink(extractTag(item, "link"));
                alert.setDate(extractTag(item, "pubDate"));

                // Coordinates
                try {
                    String lat = extractTag(item, "geo:lat");
                    String lon = extractTag(item, "geo:long");
                    if (lat != null) alert.setLat(Double.parseDouble(lat));
                    if (lon != null) alert.setLon(Double.parseDouble(lon));
                } catch (NumberFormatException e) { /* skip */ }

                // Resolve country from title (GDACS titles include country name)
                alert.setCountry(extractCountryFromTitle(alert.getTitle()));
                alert.setIso3(resolveIso3(alert.getCountry()));

                // Only keep Orange/Red alerts or significant events
                String level = alert.getAlertLevel();
                if (level != null && (level.contains("Orange") || level.contains("Red"))) {
                    alerts.add(alert);
                } else if (alert.getTitle() != null && alert.getTitle().contains("Green") == false) {
                    alerts.add(alert);
                }
            }

            log.info("GDACS: {} alerts fetched, {} significant", items.length - 1, alerts.size());
            return alerts;

        } catch (Exception e) {
            log.warn("GDACS fetch failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get alerts for a specific country (by ISO3).
     */
    public List<DisasterAlert> getAlertsForCountry(String iso3) {
        return getCurrentAlerts().stream()
            .filter(a -> iso3.equals(a.getIso3()))
            .collect(Collectors.toList());
    }

    /**
     * Get summary text for Qwen context.
     */
    public String getAlertsSummary() {
        List<DisasterAlert> alerts = getCurrentAlerts();
        if (alerts.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("ACTIVE NATURAL DISASTERS (GDACS):\n");
        for (DisasterAlert a : alerts) {
            String type = switch (a.getEventType() != null ? a.getEventType() : "") {
                case "EQ" -> "Earthquake";
                case "TC" -> "Cyclone";
                case "FL" -> "Flood";
                case "DR" -> "Drought";
                case "VO" -> "Volcano";
                case "WF" -> "Wildfire";
                default -> a.getEventType();
            };
            sb.append("  [").append(a.getAlertLevel()).append("] ")
              .append(type).append(": ").append(a.getTitle() != null ? a.getTitle() : "")
              .append("\n");
        }
        return sb.toString();
    }

    private String extractTag(String xml, String tag) {
        int start = xml.indexOf("<" + tag);
        if (start < 0) return null;
        start = xml.indexOf(">", start) + 1;
        int end = xml.indexOf("</" + tag + ">", start);
        if (end < 0) return null;
        String val = xml.substring(start, end).trim();
        // Strip CDATA
        if (val.startsWith("<![CDATA[")) val = val.substring(9, val.length() - 3);
        return val.isEmpty() ? null : val;
    }

    private String extractCountryFromTitle(String title) {
        if (title == null) return null;
        // GDACS format: "Color eventtype (...) in COUNTRY date"
        int inIdx = title.lastIndexOf(" in ");
        if (inIdx < 0) return null;
        String after = title.substring(inIdx + 4);
        // Remove date part (after last comma or digit sequence)
        int dateIdx = after.indexOf(" on ");
        if (dateIdx < 0) dateIdx = after.indexOf(" 1");
        if (dateIdx < 0) dateIdx = after.indexOf(" 2");
        if (dateIdx > 0) after = after.substring(0, dateIdx);
        return after.trim();
    }

    private String resolveIso3(String country) {
        if (country == null) return null;
        String lower = country.toLowerCase();
        // Common GDACS country names → ISO3
        return com.crisismonitor.config.MonitoredCountries.CRISIS_COUNTRIES.stream()
            .filter(iso -> {
                String name = com.crisismonitor.config.MonitoredCountries.getName(iso);
                return name != null && (lower.contains(name.toLowerCase()) || name.toLowerCase().contains(lower));
            })
            .findFirst()
            .orElse(null);
    }
}
