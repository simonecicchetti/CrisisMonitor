package com.crisismonitor.service;

import com.crisismonitor.config.MonitoredCountries;
import com.crisismonitor.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;

/**
 * Aggregates all per-country data into a single CountryProfileData response.
 * This is the backend for the country detail modal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CountryProfileService {

    private final RiskScoreService riskScoreService;
    private final GDELTService gdeltService;
    private final ReliefWebService reliefWebService;
    private final CurrencyService currencyService;
    private final OpenMeteoService openMeteoService;
    private final FewsNetService fewsNetService;
    private final DTMService dtmService;
    private final UNHCRService unhcrService;
    private final HungerMapService hungerMapService;
    private final WorldBankService worldBankService;
    private final TrendTrackingService trendTrackingService;
    private final DataFreshnessService dataFreshnessService;
    private final WHODiseaseOutbreakService whoDiseaseOutbreakService;

    // ISO3 → ISO2 reverse mapping (built from RiskScoreService's ISO2→ISO3 map)
    private static final Map<String, String> ISO3_TO_ISO2 = Map.ofEntries(
            Map.entry("SDN", "SD"), Map.entry("SSD", "SS"), Map.entry("SOM", "SO"),
            Map.entry("ETH", "ET"), Map.entry("COD", "CD"), Map.entry("NGA", "NG"),
            Map.entry("MLI", "ML"), Map.entry("BFA", "BF"), Map.entry("NER", "NE"),
            Map.entry("TCD", "TD"), Map.entry("CAF", "CF"), Map.entry("MOZ", "MZ"),
            Map.entry("KEN", "KE"), Map.entry("UGA", "UG"), Map.entry("ZWE", "ZW"),
            Map.entry("YEM", "YE"), Map.entry("AFG", "AF"), Map.entry("IRQ", "IQ"),
            Map.entry("SYR", "SY"), Map.entry("LBN", "LB"), Map.entry("IRN", "IR"),
            Map.entry("PAK", "PK"), Map.entry("BGD", "BD"), Map.entry("IND", "IN"),
            Map.entry("MMR", "MM"),
            Map.entry("PHL", "PH"), Map.entry("IDN", "ID"), Map.entry("VNM", "VN"),
            Map.entry("HTI", "HT"), Map.entry("PER", "PE"), Map.entry("COL", "CO"),
            Map.entry("ECU", "EC"), Map.entry("GTM", "GT"), Map.entry("HND", "HN"),
            Map.entry("UKR", "UA"), Map.entry("PSE", "PS"), Map.entry("LBY", "LY"),
            Map.entry("VEN", "VE"), Map.entry("SLV", "SV"), Map.entry("NIC", "NI"),
            Map.entry("MEX", "MX"), Map.entry("CUB", "CU"), Map.entry("PAN", "PA"),
            Map.entry("USA", "US"), Map.entry("CAN", "CA"),
            Map.entry("RWA", "RW"), Map.entry("BDI", "BI"), Map.entry("CMR", "CM"),
            Map.entry("JOR", "JO"), Map.entry("NPL", "NP"), Map.entry("BOL", "BO")
    );

    /**
     * Get complete country profile by ISO3 code.
     */
    @Cacheable(value = "countryProfileAggregated", key = "#iso3", unless = "#result == null")
    public CountryProfileData getProfile(String iso3) {
        log.info("Building aggregated country profile for {}", iso3);
        long start = System.currentTimeMillis();

        String iso2 = ISO3_TO_ISO2.get(iso3);
        String name = MonitoredCountries.getName(iso3);
        String region = MonitoredCountries.getRegion(iso3);

        CountryProfileData.CountryProfileDataBuilder profile = CountryProfileData.builder()
                .iso3(iso3)
                .iso2(iso2)
                .name(name)
                .region(region);

        // 1. Risk Score (contains trend, persistence, drivers, confidence)
        loadRiskScore(profile, iso2, iso3);

        // 2. Food Security (IPC + FCS/rCSI)
        loadFoodSecurity(profile, iso2, iso3);

        // 3. Climate
        loadClimate(profile, iso2);

        // 4. Conflict (GDELT)
        loadConflict(profile, iso3);

        // 5. Economy
        loadEconomy(profile, iso2, iso3);

        // 6. Displacement
        loadDisplacement(profile, iso3);

        // 7. Recent ReliefWeb reports
        loadReports(profile, iso3);

        // 8. Trend history (sparkline data)
        loadTrendHistory(profile, iso3);

        // 9. WHO Disease Outbreaks
        loadDiseaseOutbreaks(profile, iso3);

        // 10. Data freshness indicators
        loadDataFreshness(profile);

        long duration = System.currentTimeMillis() - start;
        log.info("Country profile for {} built in {}ms", iso3, duration);

        return profile.build();
    }

    private void loadRiskScore(CountryProfileData.CountryProfileDataBuilder profile, String iso2, String iso3) {
        if (iso2 == null) return;
        try {
            RiskScore risk = riskScoreService.calculateRiskScore(iso2);
            if (risk != null) {
                profile.score(risk.getScore())
                       .riskLevel(risk.getRiskLevel())
                       .climateScore(risk.getClimateScore())
                       .conflictScore(risk.getConflictScore())
                       .economicScore(risk.getEconomicScore())
                       .foodSecurityScore(risk.getFoodSecurityScore())
                       .drivers(risk.getDrivers())
                       .confidence(risk.getConfidence())
                       .confidenceNote(risk.getConfidenceNote())
                       .trend(risk.getTrend())
                       .trendIcon(risk.getTrendIcon())
                       .scoreDelta(risk.getScoreDelta())
                       .persistenceLabel(risk.getPersistenceLabel())
                       .persistenceDays(risk.getPersistenceDays())
                       .horizon(risk.getHorizon())
                       .horizonReason(risk.getHorizonReason());
            }
        } catch (Exception e) {
            log.debug("No risk score for {}: {}", iso3, e.getMessage());
        }
    }

    private void loadFoodSecurity(CountryProfileData.CountryProfileDataBuilder profile, String iso2, String iso3) {
        // IPC phase from FEWS NET
        if (iso2 != null) {
            try {
                IPCAlert ipc = fewsNetService.getLatestIPCPhase(iso2);
                if (ipc != null) {
                    profile.ipcPhase(ipc.getIpcPhase())
                           .ipcDescription(ipc.getPhaseDescription());
                }
            } catch (Exception e) {
                log.debug("No IPC data for {}: {}", iso3, e.getMessage());
            }
        }

        // People affected from HungerMap IPC data
        try {
            Map<String, Country> ipcData = hungerMapService.getIpcData();
            Country countryIpc = ipcData.get(iso3);
            if (countryIpc != null) {
                profile.peoplePhase3to5(countryIpc.getPeoplePhase3to5())
                       .percentPhase3to5(countryIpc.getPercentPhase3to5())
                       .peoplePhase4to5(countryIpc.getPeoplePhase4to5())
                       .percentPhase4to5(countryIpc.getPercentPhase4to5());
            }
        } catch (Exception e) {
            log.debug("No HungerMap IPC for {}: {}", iso3, e.getMessage());
        }

        // FCS / rCSI from food security metrics
        try {
            List<FoodSecurityMetrics> metrics = hungerMapService.getFoodSecurityMetrics();
            metrics.stream()
                    .filter(m -> iso3.equals(m.getIso3()))
                    .findFirst()
                    .ifPresent(m -> {
                        profile.fcsPrevalence(m.getFcsPrevalence())
                               .fcsPeople(m.getFcsPeople())
                               .rcsiPrevalence(m.getRcsiPrevalence())
                               .rcsiPeople(m.getRcsiPeople());
                    });
        } catch (Exception e) {
            log.debug("No FCS/rCSI for {}: {}", iso3, e.getMessage());
        }
    }

    private void loadClimate(CountryProfileData.CountryProfileDataBuilder profile, String iso2) {
        if (iso2 == null) return;
        try {
            PrecipitationAnomaly precip = openMeteoService.getPrecipitationAnomaly(iso2);
            if (precip != null) {
                profile.precipAnomaly(precip.getAnomalyPercent())
                       .precipCategory(precip.getSpiCategory())
                       .precipRiskScore(precip.getRiskScore());
            }
        } catch (Exception e) {
            log.debug("No climate data for {}: {}", iso2, e.getMessage());
        }
    }

    private void loadConflict(CountryProfileData.CountryProfileDataBuilder profile, String iso3) {
        try {
            MediaSpike spike = gdeltService.getConflictSpikeIndex(iso3);
            if (spike != null) {
                profile.gdeltZScore(spike.getZScore())
                       .spikeLevel(spike.getSpikeLevel())
                       .articles7d(spike.getArticlesLast7Days());

                // Headlines
                if (spike.getTopHeadlines() != null && !spike.getTopHeadlines().isEmpty()) {
                    List<CountryProfileData.HeadlineItem> headlines = spike.getTopHeadlines().stream()
                            .limit(5)
                            .map(title -> CountryProfileData.HeadlineItem.builder()
                                    .title(title)
                                    .build())
                            .collect(Collectors.toList());
                    profile.headlines(headlines);
                }
            }
        } catch (Exception e) {
            log.debug("No conflict data for {}: {}", iso3, e.getMessage());
        }
    }

    private void loadEconomy(CountryProfileData.CountryProfileDataBuilder profile, String iso2, String iso3) {
        // Currency
        if (iso2 != null) {
            try {
                CurrencyData currency = currencyService.getCurrencyData(iso2);
                if (currency != null) {
                    profile.currencyChange30d(currency.getChange30d())
                           .currencyCode(currency.getCurrencyCode());
                }
            } catch (Exception e) {
                log.debug("No currency data for {}: {}", iso2, e.getMessage());
            }
        }

        // Inflation
        try {
            List<EconomicIndicator> inflation = worldBankService.getInflationData();
            if (inflation != null) {
                inflation.stream()
                        .filter(i -> iso3.equalsIgnoreCase(i.getIso3()))
                        .findFirst()
                        .ifPresent(i -> {
                            profile.inflationRate(i.getValue())
                                   .inflationYear(i.getYear() != null ? String.valueOf(i.getYear()) : null);
                        });
            }
        } catch (Exception e) {
            log.debug("No inflation for {}: {}", iso3, e.getMessage());
        }
    }

    private void loadDisplacement(CountryProfileData.CountryProfileDataBuilder profile, String iso3) {
        // IDPs from DTM
        try {
            List<MobilityStock> dtmData = dtmService.getCountryLevelIdps();
            if (dtmData != null) {
                dtmData.stream()
                        .filter(d -> iso3.equalsIgnoreCase(d.getIso3()))
                        .findFirst()
                        .ifPresent(d -> profile.idps(d.getIdps()));
            }
        } catch (Exception e) {
            log.debug("No DTM data for {}: {}", iso3, e.getMessage());
        }

        // Refugees from UNHCR
        try {
            List<MobilityStock> unhcrData = unhcrService.getDisplacementByOrigin();
            if (unhcrData != null) {
                unhcrData.stream()
                        .filter(d -> iso3.equalsIgnoreCase(d.getIso3()))
                        .findFirst()
                        .ifPresent(d -> profile.refugees(d.getRefugees()));
            }
        } catch (Exception e) {
            log.debug("No UNHCR data for {}: {}", iso3, e.getMessage());
        }
    }

    private void loadTrendHistory(CountryProfileData.CountryProfileDataBuilder profile, String iso3) {
        try {
            var history = trendTrackingService.getScoreHistory(iso3);
            if (history != null && !history.isEmpty()) {
                List<CountryProfileData.TrendPoint> points = history.stream()
                        .map(e -> CountryProfileData.TrendPoint.builder()
                                .date(e.getKey().toString())
                                .score(e.getValue())
                                .build())
                        .collect(Collectors.toList());
                profile.trendHistory(points);
            }
        } catch (Exception e) {
            log.debug("No trend history for {}: {}", iso3, e.getMessage());
        }
    }

    private void loadDiseaseOutbreaks(CountryProfileData.CountryProfileDataBuilder profile, String iso3) {
        try {
            var outbreaks = whoDiseaseOutbreakService.getOutbreaksForCountry(iso3);
            if (outbreaks != null && !outbreaks.isEmpty()) {
                List<CountryProfileData.DiseaseOutbreakItem> items = outbreaks.stream()
                        .limit(5)
                        .map(o -> CountryProfileData.DiseaseOutbreakItem.builder()
                                .disease(o.getDisease())
                                .title(o.getTitle())
                                .date(o.getPublishedDate())
                                .timeAgo(o.getTimeAgo())
                                .url(o.getUrl())
                                .build())
                        .collect(Collectors.toList());
                profile.diseaseOutbreaks(items);
            }
        } catch (Exception e) {
            log.debug("No WHO DON data for {}: {}", iso3, e.getMessage());
        }
    }

    private void loadDataFreshness(CountryProfileData.CountryProfileDataBuilder profile) {
        try {
            var freshness = dataFreshnessService.getDataFreshness();
            if (freshness != null && freshness.getSources() != null) {
                Map<String, String> freshnessMap = new LinkedHashMap<>();
                for (var src : freshness.getSources()) {
                    freshnessMap.put(src.getSourceName(), src.getStatus());
                }
                profile.dataFreshness(freshnessMap);
            }
        } catch (Exception e) {
            log.debug("No freshness data: {}", e.getMessage());
        }
    }

    private void loadReports(CountryProfileData.CountryProfileDataBuilder profile, String iso3) {
        try {
            var reports = reliefWebService.getLatestReports(iso3, 5);
            if (reports != null && !reports.isEmpty()) {
                List<CountryProfileData.ReportItem> items = reports.stream()
                        .map(r -> CountryProfileData.ReportItem.builder()
                                .title(r.getTitle())
                                .url(r.getUrl())
                                .source(r.getSource())
                                .date(r.getDate())
                                .format(r.getFormat())
                                .build())
                        .collect(Collectors.toList());
                profile.recentReports(items);
            }
        } catch (Exception e) {
            log.debug("No ReliefWeb reports for {}: {}", iso3, e.getMessage());
        }
    }
}
