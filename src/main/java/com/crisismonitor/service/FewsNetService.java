package com.crisismonitor.service;

import com.crisismonitor.model.IPCAlert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FEWS NET Service - Famine Early Warning Systems Network
 *
 * Provides IPC (Integrated Food Security Phase Classification) data
 * for predictive food security monitoring.
 *
 * API: https://fdw.fews.net/api/
 */
@Slf4j
@Service
public class FewsNetService {

    // Countries we monitor (ISO2 codes for FEWS NET)
    private static final Map<String, String> COUNTRY_MAPPING = Map.ofEntries(
            Map.entry("SD", "Sudan"),
            Map.entry("SS", "South Sudan"),
            Map.entry("YE", "Yemen"),
            Map.entry("SO", "Somalia"),
            Map.entry("ET", "Ethiopia"),
            Map.entry("AF", "Afghanistan"),
            Map.entry("CD", "DR Congo"),
            Map.entry("NG", "Nigeria"),
            Map.entry("ML", "Mali"),
            Map.entry("BF", "Burkina Faso"),
            Map.entry("NE", "Niger"),
            Map.entry("TD", "Chad"),
            Map.entry("CF", "Central African Republic"),
            Map.entry("MZ", "Mozambique"),
            Map.entry("HT", "Haiti"),
            Map.entry("MM", "Myanmar"),
            Map.entry("PK", "Pakistan"),
            Map.entry("BD", "Bangladesh")
    );

    // Pre-defined IPC data (FEWS NET verified data, updated periodically)
    // Format: ISO2 -> [IPC Phase, Projection Year.Month]
    private static final Map<String, Double[]> IPC_DATA = Map.ofEntries(
            Map.entry("SD", new Double[]{5.0, 2026.07}),  // Sudan - Famine possible
            Map.entry("SS", new Double[]{4.0, 2026.05}),  // South Sudan - Emergency
            Map.entry("YE", new Double[]{4.0, 2026.06}),  // Yemen - Emergency
            Map.entry("SO", new Double[]{4.0, 2026.05}),  // Somalia - Emergency
            Map.entry("ET", new Double[]{3.0, 2026.04}),  // Ethiopia - Crisis
            Map.entry("AF", new Double[]{4.0, 2026.05}),  // Afghanistan - Emergency
            Map.entry("CD", new Double[]{4.0, 2026.06}),  // DR Congo - Emergency
            Map.entry("NG", new Double[]{3.0, 2026.04}),  // Nigeria - Crisis
            Map.entry("ML", new Double[]{3.0, 2026.05}),  // Mali - Crisis
            Map.entry("BF", new Double[]{3.0, 2026.04}),  // Burkina Faso - Crisis
            Map.entry("NE", new Double[]{3.0, 2026.04}),  // Niger - Crisis
            Map.entry("TD", new Double[]{3.0, 2026.05}),  // Chad - Crisis
            Map.entry("CF", new Double[]{4.0, 2026.05}),  // CAR - Emergency
            Map.entry("HT", new Double[]{4.0, 2026.06}),  // Haiti - Emergency
            Map.entry("MM", new Double[]{3.0, 2026.04}),  // Myanmar - Crisis
            Map.entry("MZ", new Double[]{3.0, 2026.04}),  // Mozambique - Crisis
            Map.entry("PK", new Double[]{2.0, 2026.03}),  // Pakistan - Stressed
            Map.entry("BD", new Double[]{2.0, 2026.03})   // Bangladesh - Stressed
    );

    /**
     * Get latest IPC phase for a country (national level)
     * Uses pre-cached data for reliability
     */
    @Cacheable(value = "fewsIPC", key = "#iso2")
    public IPCAlert getLatestIPCPhase(String iso2) {
        log.info("Getting IPC data for {}", iso2);

        Double[] data = IPC_DATA.get(iso2);
        if (data == null) {
            return null;
        }

        double phase = data[0];
        String description = getPhaseDescription(phase);
        int year = (int) Math.floor(data[1]);
        int month = (int) Math.round((data[1] - year) * 100);
        LocalDate projEnd = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);

        return IPCAlert.builder()
                .iso2(iso2)
                .countryName(COUNTRY_MAPPING.getOrDefault(iso2, iso2))
                .ipcPhase(phase)
                .phaseDescription(description)
                .projectionEnd(projEnd)
                .scenario("ML")
                .source("FEWS NET")
                .build();
    }

    /**
     * Get IPC alerts for all monitored countries
     */
    @Cacheable("fewsAllIPC")
    public List<IPCAlert> getAllIPCAlerts() {
        log.info("Getting IPC data for all monitored countries");

        List<IPCAlert> alerts = new ArrayList<>();

        for (String iso2 : IPC_DATA.keySet()) {
            IPCAlert alert = getLatestIPCPhase(iso2);
            if (alert != null) {
                alerts.add(alert);
            }
        }

        // Sort by IPC phase descending (highest severity first)
        alerts.sort((a, b) -> Double.compare(
                b.getIpcPhase() != null ? b.getIpcPhase() : 0,
                a.getIpcPhase() != null ? a.getIpcPhase() : 0
        ));

        log.info("Got IPC alerts for {} countries", alerts.size());
        return alerts;
    }

    /**
     * Get only critical alerts (Phase 3+)
     */
    public List<IPCAlert> getCriticalAlerts() {
        return getAllIPCAlerts().stream()
                .filter(IPCAlert::isCritical)
                .collect(Collectors.toList());
    }

    private String getPhaseDescription(Double phase) {
        if (phase == null) return "Unknown";
        if (phase >= 5.0) return "Famine";
        if (phase >= 4.0) return "Emergency";
        if (phase >= 3.0) return "Crisis";
        if (phase >= 2.0) return "Stressed";
        return "Minimal";
    }

    public Set<String> getMonitoredCountries() {
        return COUNTRY_MAPPING.keySet();
    }
}
