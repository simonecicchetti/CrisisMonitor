package com.crisismonitor.controller;

import com.crisismonitor.model.*;
import com.crisismonitor.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.*;

@Slf4j
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final ClimateService climateService;
    private final MigrationService migrationService;
    private final WorldBankService worldBankService;
    private final HungerMapService hungerMapService;
    private final UNHCRService unhcrService;
    private final DTMService dtmService;
    private final GDELTService gdeltService;
    private final CacheWarmupService cacheWarmupService;

    @GetMapping("/")
    @SuppressWarnings("unchecked")
    public String index(Model model) {
        long start = System.currentTimeMillis();

        // Risk scores — from memory fallback (instant, no API calls)
        try {
            List<RiskScore> riskScores = (List<RiskScore>) cacheWarmupService.getFallback("allRiskScores");
            model.addAttribute("riskScores", riskScores != null ? riskScores : Collections.emptyList());
        } catch (Exception e) {
            model.addAttribute("riskScores", Collections.emptyList());
        }

        // Dashboard stats — from memory fallback
        try {
            Map<String, Object> stats = (Map<String, Object>) cacheWarmupService.getFallback("dashboardStats");
            model.addAttribute("stats", stats != null ? stats : getEmptyStats());
        } catch (Exception e) {
            model.addAttribute("stats", getEmptyStats());
        }

        // All other data: use memory fallback with empty defaults.
        // These populate the Drivers tab tables (Thymeleaf server-side rendered).
        // Without Redis, fetching these on every page load blocks the response for 30-60s.
        model.addAttribute("rankedCountries", safeFallback("rankedCountries", Collections.emptyList()));
        model.addAttribute("alertsByCategory", Collections.emptyMap());
        model.addAttribute("hazards", safeFallback("hazards", Collections.emptyList()));
        model.addAttribute("climateStress", safeFallback("climateStress", Collections.emptyList()));
        model.addAttribute("migrationByCountry", safeFallback("migrationByCountry", Collections.emptyMap()));
        model.addAttribute("migrationMonthly", safeFallback("migrationMonthly", Collections.emptyMap()));
        model.addAttribute("foodSecurity", safeFallback("foodSecurity", Collections.emptyList()));
        model.addAttribute("displacementStocks", safeFallback("displacementStocks", Collections.emptyList()));
        model.addAttribute("globalDisplacement", safeFallback("globalDisplacement", null));
        model.addAttribute("asylumFlows", safeFallback("asylumFlows", Collections.emptyList()));
        model.addAttribute("dtmData", safeFallback("dtmData", Collections.emptyList()));

        // GDELT conflict/media spikes (loaded async via JS for performance)
        model.addAttribute("gdeltEnabled", true);

        log.info("Page rendered in {}ms", System.currentTimeMillis() - start);
        return "index";
    }

    private Object safeFallback(String key, Object defaultValue) {
        try {
            Object val = cacheWarmupService.getFallback(key);
            return val != null ? val : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Map<String, Object> getEmptyStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("criticalCountries", 0L);
        stats.put("highRiskCountries", 0L);
        stats.put("totalPeopleAffected", 0L);
        stats.put("totalAlerts", 0);
        stats.put("activeHazards", 0);
        return stats;
    }
}
