package com.crisismonitor.service;

import com.crisismonitor.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks data freshness for all data sources.
 * Maintains last update timestamps and provides summary for dashboard transparency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataFreshnessService {

    private final FewsNetService fewsNetService;
    private final WorldBankService worldBankService;
    private final DTMService dtmService;
    private final ClimateService climateService;
    private final GDELTService gdeltService;
    private final RiskScoreService riskScoreService;
    private final CacheWarmupService cacheWarmupService;

    // Store last update times (in production, could use Redis)
    private final Map<String, LocalDateTime> lastUpdateTimes = new ConcurrentHashMap<>();
    private final Map<String, Integer> recordCounts = new ConcurrentHashMap<>();

    /**
     * Get freshness status for all main data sources.
     */
    public DataSourceStatus.DataFreshnessSummary getDataFreshness() {
        List<DataSourceStatus> sources = new ArrayList<>();

        // 1. IPC Phase (FEWS NET)
        sources.add(getIpcStatus());

        // 2. Inflation (World Bank)
        sources.add(getInflationStatus());

        // 3. IDPs (IOM DTM)
        sources.add(getIdpStatus());

        // 4. Climate/NDVI
        sources.add(getClimateStatus());

        // 5. Conflict (GDELT)
        sources.add(getConflictStatus());

        // 6. Risk Scores (Composite)
        sources.add(getRiskScoreStatus());

        return DataSourceStatus.DataFreshnessSummary.from(sources);
    }

    private DataSourceStatus getIpcStatus() {
        try {
            List<IPCAlert> alerts = fewsNetService.getCriticalAlerts();
            int count = alerts != null ? alerts.size() : 0;

            // IPC data typically has analysis periods - extract if available
            LocalDateTime dataDate = null;
            if (alerts != null && !alerts.isEmpty()) {
                IPCAlert first = alerts.get(0);
                // Use current time as fetch time since IPC updates are periodic
                dataDate = LocalDateTime.now();
            }

            updateTracking("ipc", count);

            return DataSourceStatus.create(
                    "ipc",
                    "IPC Phase (FEWS NET)",
                    lastUpdateTimes.getOrDefault("ipc", LocalDateTime.now()),
                    dataDate,
                    count
            );
        } catch (Exception e) {
            log.warn("Could not get IPC status: {}", e.getMessage());
            return DataSourceStatus.builder()
                    .sourceId("ipc")
                    .sourceName("IPC Phase (FEWS NET)")
                    .status("unavailable")
                    .statusIcon("✕")
                    .build();
        }
    }

    private DataSourceStatus getInflationStatus() {
        try {
            List<EconomicIndicator> data = worldBankService.getHighInflationCountries();
            int count = data != null ? data.size() : 0;

            // World Bank data may have a year indicator
            LocalDateTime dataDate = null;
            if (data != null && !data.isEmpty() && data.get(0).getYear() != null) {
                dataDate = LocalDateTime.of(data.get(0).getYear(), 1, 1, 0, 0);
            }

            updateTracking("inflation", count);

            DataSourceStatus status = DataSourceStatus.create(
                    "inflation",
                    "Inflation % (World Bank)",
                    lastUpdateTimes.getOrDefault("inflation", LocalDateTime.now()),
                    dataDate,
                    count
            );

            // Add note about World Bank data lag
            if (dataDate != null && dataDate.getYear() < LocalDateTime.now().getYear()) {
                status.setNote("Data from " + dataDate.getYear());
            }

            return status;
        } catch (Exception e) {
            log.warn("Could not get inflation status: {}", e.getMessage());
            return DataSourceStatus.builder()
                    .sourceId("inflation")
                    .sourceName("Inflation % (World Bank)")
                    .status("unavailable")
                    .statusIcon("✕")
                    .build();
        }
    }

    private DataSourceStatus getIdpStatus() {
        try {
            // Use memory fallback only — DTM downloads a large CSV that can cause OOM
            @SuppressWarnings("unchecked")
            List<MobilityStock> data = (List<MobilityStock>) cacheWarmupService.getFallback("dtmData");
            int count = data != null ? data.size() : 0;

            updateTracking("idps", count);

            return DataSourceStatus.create(
                    "idps",
                    "IDPs (IOM DTM)",
                    lastUpdateTimes.getOrDefault("idps", LocalDateTime.now()),
                    null,
                    count
            );
        } catch (Exception e) {
            log.warn("Could not get IDP status: {}", e.getMessage());
            return DataSourceStatus.builder()
                    .sourceId("idps")
                    .sourceName("IDPs (IOM DTM)")
                    .status("unavailable")
                    .statusIcon("✕")
                    .build();
        }
    }

    private DataSourceStatus getClimateStatus() {
        try {
            List<ClimateData> data = climateService.getCountriesWithClimateStress();
            int count = data != null ? data.size() : 0;

            updateTracking("climate", count);

            return DataSourceStatus.create(
                    "climate",
                    "NDVI/Climate Stress",
                    lastUpdateTimes.getOrDefault("climate", LocalDateTime.now()),
                    null,
                    count
            );
        } catch (Exception e) {
            log.warn("Could not get climate status: {}", e.getMessage());
            return DataSourceStatus.builder()
                    .sourceId("climate")
                    .sourceName("NDVI/Climate Stress")
                    .status("unavailable")
                    .statusIcon("✕")
                    .build();
        }
    }

    private DataSourceStatus getConflictStatus() {
        try {
            // Use memory fallback only — never call GDELT live from profile thread (blocks with rate limiting)
            @SuppressWarnings("unchecked")
            List<MediaSpike> data = (List<MediaSpike>) cacheWarmupService.getFallback("gdeltAllSpikes");
            int count = data != null ? data.size() : 0;

            updateTracking("conflict", count);

            return DataSourceStatus.create(
                    "conflict",
                    "Conflict Events (GDELT)",
                    lastUpdateTimes.getOrDefault("conflict", LocalDateTime.now()),
                    null,
                    count
            );
        } catch (Exception e) {
            log.warn("Could not get conflict status: {}", e.getMessage());
            return DataSourceStatus.builder()
                    .sourceId("conflict")
                    .sourceName("Conflict Events (GDELT)")
                    .status("unavailable")
                    .statusIcon("✕")
                    .build();
        }
    }

    private DataSourceStatus getRiskScoreStatus() {
        try {
            // Use memory fallback only — getAllRiskScores is sync=true and can block
            @SuppressWarnings("unchecked")
            List<RiskScore> data = (List<RiskScore>) cacheWarmupService.getFallback("allRiskScores");
            int count = data != null ? data.size() : 0;

            LocalDateTime calcTime = null;
            if (data != null && !data.isEmpty() && data.get(0).getCalculatedAt() != null) {
                calcTime = data.get(0).getCalculatedAt();
            }

            updateTracking("risk", count);

            return DataSourceStatus.create(
                    "risk",
                    "Composite Risk Scores",
                    calcTime != null ? calcTime : lastUpdateTimes.getOrDefault("risk", LocalDateTime.now()),
                    calcTime,
                    count
            );
        } catch (Exception e) {
            log.warn("Could not get risk score status: {}", e.getMessage());
            return DataSourceStatus.builder()
                    .sourceId("risk")
                    .sourceName("Composite Risk Scores")
                    .status("unavailable")
                    .statusIcon("✕")
                    .build();
        }
    }

    /**
     * Update tracking for a data source when it's successfully fetched.
     */
    private void updateTracking(String sourceId, int count) {
        if (count > 0) {
            lastUpdateTimes.put(sourceId, LocalDateTime.now());
            recordCounts.put(sourceId, count);
        }
    }

    /**
     * Manually mark a source as updated (called by services after fetch).
     */
    public void markUpdated(String sourceId) {
        lastUpdateTimes.put(sourceId, LocalDateTime.now());
    }
}
