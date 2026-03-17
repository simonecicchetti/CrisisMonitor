package com.crisismonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class StructuralIndexService {

    private final ObjectMapper objectMapper;

    private Map<String, CountryIndices> countryData = new HashMap<>();
    private Map<String, WatchlistInfo> watchlists = new HashMap<>();
    private Map<String, Set<String>> countryWatchlists = new HashMap<>(); // iso3 → set of watchlist keys

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("data/structural-indices.json");
            JsonNode root = objectMapper.readTree(resource.getInputStream());

            // Load watchlists
            JsonNode watchlistsNode = root.get("watchlists");
            if (watchlistsNode != null) {
                watchlistsNode.fieldNames().forEachRemaining(key -> {
                    JsonNode wl = watchlistsNode.get(key);
                    WatchlistInfo info = new WatchlistInfo();
                    info.setKey(key);
                    info.setName(wl.get("name").asText());
                    info.setSource(wl.get("source").asText());
                    List<String> countries = new ArrayList<>();
                    wl.get("countries").forEach(c -> {
                        String iso3 = c.asText();
                        countries.add(iso3);
                        countryWatchlists.computeIfAbsent(iso3, k -> new LinkedHashSet<>()).add(key);
                    });
                    info.setCountries(countries);
                    watchlists.put(key, info);
                });
            }

            // Load country indices
            JsonNode countriesNode = root.get("countries");
            if (countriesNode != null) {
                countriesNode.fieldNames().forEachRemaining(iso3 -> {
                    JsonNode c = countriesNode.get(iso3);
                    CountryIndices indices = new CountryIndices();
                    indices.setIso3(iso3);

                    if (c.has("fsi")) {
                        indices.setFsiScore(c.get("fsi").get("score").asDouble());
                        indices.setFsiRank(c.get("fsi").get("rank").asInt());
                    }
                    if (c.has("gpi")) {
                        indices.setGpiScore(c.get("gpi").get("score").asDouble());
                        indices.setGpiRank(c.get("gpi").get("rank").asInt());
                    }

                    // Calculate consensus count: how many watchlists flag this country
                    Set<String> wls = countryWatchlists.getOrDefault(iso3, Collections.emptySet());
                    indices.setWatchlistCount(wls.size());
                    indices.setWatchlists(new ArrayList<>(wls));

                    // Structural vulnerability tier based on FSI
                    if (indices.getFsiScore() >= 100) indices.setFsiTier("Very High Alert");
                    else if (indices.getFsiScore() >= 90) indices.setFsiTier("High Alert");
                    else if (indices.getFsiScore() >= 80) indices.setFsiTier("Alert");
                    else if (indices.getFsiScore() >= 70) indices.setFsiTier("Warning");
                    else indices.setFsiTier("Moderate");

                    // Peace tier based on GPI
                    if (indices.getGpiScore() >= 3.0) indices.setGpiTier("Very Low Peace");
                    else if (indices.getGpiScore() >= 2.5) indices.setGpiTier("Low Peace");
                    else if (indices.getGpiScore() >= 2.0) indices.setGpiTier("Medium Peace");
                    else indices.setGpiTier("High Peace");

                    countryData.put(iso3, indices);
                });
            }

            log.info("Loaded structural indices: {} countries, {} watchlists",
                    countryData.size(), watchlists.size());
        } catch (IOException e) {
            log.error("Failed to load structural indices: {}", e.getMessage());
        }
    }

    public CountryIndices getCountryIndices(String iso3) {
        return countryData.get(iso3);
    }

    public Map<String, CountryIndices> getAllCountryIndices() {
        return Collections.unmodifiableMap(countryData);
    }

    public List<WatchlistInfo> getAllWatchlists() {
        return new ArrayList<>(watchlists.values());
    }

    /**
     * Get watchlist details for a specific country, with rank info where applicable
     */
    public List<WatchlistEntry> getCountryWatchlistEntries(String iso3) {
        Set<String> wlKeys = countryWatchlists.getOrDefault(iso3, Collections.emptySet());
        List<WatchlistEntry> entries = new ArrayList<>();
        for (String key : wlKeys) {
            WatchlistInfo wl = watchlists.get(key);
            if (wl != null) {
                WatchlistEntry entry = new WatchlistEntry();
                entry.setKey(key);
                entry.setName(wl.getName());
                entry.setSource(wl.getSource());
                int idx = wl.getCountries().indexOf(iso3);
                entry.setRank(idx >= 0 ? idx + 1 : null);
                entries.add(entry);
            }
        }
        return entries;
    }

    // DTOs

    @Data
    public static class CountryIndices {
        private String iso3;
        private double fsiScore;
        private int fsiRank;
        private String fsiTier;
        private double gpiScore;
        private int gpiRank;
        private String gpiTier;
        private int watchlistCount;
        private List<String> watchlists = new ArrayList<>();
    }

    @Data
    public static class WatchlistInfo {
        private String key;
        private String name;
        private String source;
        private List<String> countries = new ArrayList<>();
    }

    @Data
    public static class WatchlistEntry {
        private String key;
        private String name;
        private String source;
        private Integer rank;
    }
}
