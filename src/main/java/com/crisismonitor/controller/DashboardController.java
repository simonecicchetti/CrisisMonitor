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

    @GetMapping("/")
    public String index(Model model) {
        // Get dashboard statistics with fallback
        try {
            Map<String, Object> stats = dashboardService.getDashboardStats();
            model.addAttribute("stats", stats != null ? stats : getEmptyStats());
        } catch (Exception e) {
            log.error("Error loading dashboard stats: {}", e.getMessage());
            model.addAttribute("stats", getEmptyStats());
        }

        // Get top countries in crisis
        try {
            List<Country> rankedCountries = dashboardService.getCountriesRankedByCrisis();
            model.addAttribute("rankedCountries", rankedCountries != null ? rankedCountries : Collections.emptyList());
        } catch (Exception e) {
            log.error("Error loading ranked countries: {}", e.getMessage());
            model.addAttribute("rankedCountries", Collections.emptyList());
        }

        // Get alerts by category
        try {
            Map<String, List<Alert>> alertsByCategory = dashboardService.getAlertsByCategory();
            model.addAttribute("alertsByCategory", alertsByCategory != null ? alertsByCategory : Collections.emptyMap());
        } catch (Exception e) {
            log.error("Error loading alerts: {}", e.getMessage());
            model.addAttribute("alertsByCategory", Collections.emptyMap());
        }

        // Get active hazards
        try {
            List<Hazard> hazards = dashboardService.getActiveHazards();
            model.addAttribute("hazards", hazards != null ? hazards : Collections.emptyList());
        } catch (Exception e) {
            log.error("Error loading hazards: {}", e.getMessage());
            model.addAttribute("hazards", Collections.emptyList());
        }

        // Climate data - countries with climate stress (drought)
        try {
            List<ClimateData> climateStress = climateService.getCountriesWithClimateStress();
            model.addAttribute("climateStress", climateStress != null ? climateStress : Collections.emptyList());
        } catch (Exception e) {
            log.error("Error loading climate data: {}", e.getMessage());
            model.addAttribute("climateStress", Collections.emptyList());
        }

        // Migration data - Darien Gap crossings by nationality
        try {
            Map<String, Long> migrationByCountry = migrationService.getMigrationByCountry(12);
            model.addAttribute("migrationByCountry", migrationByCountry != null ? migrationByCountry : Collections.emptyMap());
        } catch (Exception e) {
            log.error("Error loading migration by country: {}", e.getMessage());
            model.addAttribute("migrationByCountry", Collections.emptyMap());
        }

        // Migration monthly totals
        try {
            Map<String, Long> migrationMonthly = migrationService.getMonthlyTotals(6);
            model.addAttribute("migrationMonthly", migrationMonthly != null ? migrationMonthly : Collections.emptyMap());
        } catch (Exception e) {
            log.error("Error loading migration monthly: {}", e.getMessage());
            model.addAttribute("migrationMonthly", Collections.emptyMap());
        }

        // Economic data - high inflation countries
        try {
            List<EconomicIndicator> highInflation = worldBankService.getHighInflationCountries();
            model.addAttribute("highInflation", highInflation != null ? highInflation : Collections.emptyList());
        } catch (Exception e) {
            log.error("Error loading inflation data: {}", e.getMessage());
            model.addAttribute("highInflation", Collections.emptyList());
        }

        // Food security metrics
        try {
            List<FoodSecurityMetrics> foodSecurity = hungerMapService.getFoodSecurityMetrics();
            model.addAttribute("foodSecurity", foodSecurity != null ? foodSecurity : Collections.emptyList());
        } catch (Exception e) {
            log.error("Error loading food security: {}", e.getMessage());
            model.addAttribute("foodSecurity", Collections.emptyList());
        }

        // UNHCR displacement data
        try {
            List<MobilityStock> displacementStocks = unhcrService.getDisplacementByOrigin();
            model.addAttribute("displacementStocks", displacementStocks != null ? displacementStocks : Collections.emptyList());
        } catch (Exception e) {
            log.error("Error loading displacement stocks: {}", e.getMessage());
            model.addAttribute("displacementStocks", Collections.emptyList());
        }

        try {
            MobilityStock globalDisplacement = unhcrService.getGlobalSummary();
            model.addAttribute("globalDisplacement", globalDisplacement);
        } catch (Exception e) {
            log.error("Error loading global displacement: {}", e.getMessage());
            model.addAttribute("globalDisplacement", null);
        }

        try {
            List<MobilityFlow> asylumFlows = unhcrService.getAsylumApplications();
            model.addAttribute("asylumFlows", asylumFlows != null ? asylumFlows : Collections.emptyList());
        } catch (Exception e) {
            log.error("Error loading asylum flows: {}", e.getMessage());
            model.addAttribute("asylumFlows", Collections.emptyList());
        }

        // IOM DTM data
        try {
            List<MobilityStock> dtmData = dtmService.getCountryLevelIdps();
            model.addAttribute("dtmData", dtmData != null ? dtmData : Collections.emptyList());
        } catch (Exception e) {
            log.error("Error loading DTM data: {}", e.getMessage());
            model.addAttribute("dtmData", Collections.emptyList());
        }

        // GDELT conflict/media spikes (loaded async via JS for performance)
        model.addAttribute("gdeltEnabled", true);

        return "index";
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
