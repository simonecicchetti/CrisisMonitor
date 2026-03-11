package com.crisismonitor.service;

import com.crisismonitor.model.Alert;
import com.crisismonitor.model.Country;
import com.crisismonitor.model.Hazard;
import com.crisismonitor.util.CountryCoordinates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final HungerMapService hungerMapService;
    private final HazardService hazardService;

    /**
     * Get enriched country data with IPC and severity information
     */
    public List<Country> getEnrichedCountries() {
        List<Country> countries = hungerMapService.getCountries();
        Map<String, Country> ipcData = hungerMapService.getIpcData();
        Map<String, Integer> severityData = hungerMapService.getSeverityData();

        // Enrich countries with IPC and severity data
        for (Country country : countries) {
            String iso3 = country.getIso3();

            // Add geographic coordinates
            country.setLatitude(CountryCoordinates.getLatitude(iso3));
            country.setLongitude(CountryCoordinates.getLongitude(iso3));

            // Add severity tier
            if (severityData.containsKey(iso3)) {
                country.setSeverityTier(severityData.get(iso3));
            }

            // Add IPC data
            if (ipcData.containsKey(iso3)) {
                Country ipc = ipcData.get(iso3);
                country.setPeoplePhase3to5(ipc.getPeoplePhase3to5());
                country.setPercentPhase3to5(ipc.getPercentPhase3to5());
                country.setPeoplePhase4to5(ipc.getPeoplePhase4to5());
                country.setPercentPhase4to5(ipc.getPercentPhase4to5());
                country.setIpcAnalysisPeriod(ipc.getIpcAnalysisPeriod());
                country.setAlertLevel(ipc.getAlertLevel());
            }

            // If no IPC data, derive alert level from severity tier
            if (country.getAlertLevel() == null && country.getSeverityTier() != null) {
                country.setAlertLevel(tierToAlertLevel(country.getSeverityTier()));
            }
        }

        return countries;
    }

    /**
     * Get countries sorted by crisis severity (most severe first)
     */
    public List<Country> getCountriesRankedByCrisis() {
        return getEnrichedCountries().stream()
                .filter(c -> c.getPercentPhase3to5() != null && c.getPercentPhase3to5() > 0)
                .sorted((a, b) -> {
                    // Sort by percent in crisis (descending)
                    return Double.compare(
                            b.getPercentPhase3to5() != null ? b.getPercentPhase3to5() : 0,
                            a.getPercentPhase3to5() != null ? a.getPercentPhase3to5() : 0
                    );
                })
                .limit(30) // Top 30 countries
                .collect(Collectors.toList());
    }

    /**
     * Get alerts grouped by category
     */
    public Map<String, List<Alert>> getAlertsByCategory() {
        return hungerMapService.getAlerts().stream()
                .collect(Collectors.groupingBy(Alert::getCategory));
    }

    /**
     * Get active hazards
     */
    public List<Hazard> getActiveHazards() {
        return hazardService.getActiveHazards();
    }

    /**
     * Get summary statistics for the dashboard
     */
    public Map<String, Object> getDashboardStats() {
        List<Country> countries = getEnrichedCountries();
        List<Alert> alerts = hungerMapService.getAlerts();
        List<Hazard> hazards = hazardService.getActiveHazards();

        Map<String, Object> stats = new HashMap<>();

        // Countries in crisis
        long criticalCount = countries.stream()
                .filter(c -> "CRITICAL".equals(c.getAlertLevel()))
                .count();
        long highCount = countries.stream()
                .filter(c -> "HIGH".equals(c.getAlertLevel()))
                .count();

        // Total people affected (IPC phase 3+)
        long totalAffected = countries.stream()
                .filter(c -> c.getPeoplePhase3to5() != null)
                .mapToLong(Country::getPeoplePhase3to5)
                .sum();

        // Alert counts by category
        Map<String, Long> alertsByCategory = alerts.stream()
                .collect(Collectors.groupingBy(Alert::getCategory, Collectors.counting()));

        stats.put("criticalCountries", criticalCount);
        stats.put("highRiskCountries", highCount);
        stats.put("totalPeopleAffected", totalAffected);
        stats.put("totalAlerts", alerts.size());
        stats.put("alertsByCategory", alertsByCategory);
        stats.put("activeHazards", hazards.size());

        return stats;
    }

    private String tierToAlertLevel(Integer tier) {
        if (tier == null) return "NO_DATA";
        return switch (tier) {
            case 1 -> "CRITICAL";
            case 2 -> "HIGH";
            case 3 -> "MEDIUM";
            default -> "LOW";
        };
    }
}
