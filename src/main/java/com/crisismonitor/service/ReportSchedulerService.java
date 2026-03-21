package com.crisismonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Pre-generates intelligence reports on a schedule.
 * 7 topics × 6 regions = 42 reports, stored in Firestore.
 * Users read from Firestore — zero Claude API calls per request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportSchedulerService {

    private final TopicReportService topicReportService;
    private final FirestoreService firestoreService;
    private final ObjectMapper objectMapper;
    private final CountryAnalysisGenerator countryAnalysisGenerator;

    private static final List<String> TOPICS = List.of(
        "migration", "food-crisis", "conflict", "political", "climate", "health", "economic"
    );

    private static final List<String> REGIONS = List.of(
        "lac", "mena", "east-africa", "west-africa", "asia", "europe"
    );

    /**
     * Generate all 42 reports weekly (Sunday 4:00 UTC).
     * In production, change to every 12 hours.
     */
    @Scheduled(cron = "0 0 4 * * SUN")
    public void generateAllReports() {
        log.info("=== Starting scheduled report generation ({} topics × {} regions = {} reports) ===",
            TOPICS.size(), REGIONS.size(), TOPICS.size() * REGIONS.size());

        int success = 0;
        int failed = 0;
        long startTime = System.currentTimeMillis();

        for (String topic : TOPICS) {
            for (String region : REGIONS) {
                try {
                    log.info("Generating report: {} / {}", topic, region);
                    var report = topicReportService.generateReport(topic, region, 7);

                    if (report != null) {
                        // Serialize to Map for Firestore
                        @SuppressWarnings("unchecked")
                        Map<String, Object> reportData = objectMapper.convertValue(report, Map.class);
                        reportData.put("generatedAt", LocalDateTime.now().toString());
                        reportData.put("timestamp", System.currentTimeMillis());

                        // Store with key: topic_region
                        String docId = topic + "_" + region;
                        firestoreService.saveGeneratedReport(docId, reportData);
                        success++;
                        log.info("  Saved report: {}", docId);
                    }

                    // Delay between reports to respect Claude API rate limits
                    Thread.sleep(5000);

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Report generation interrupted");
                    return;
                } catch (Exception e) {
                    failed++;
                    log.error("  Failed to generate {} / {}: {}", topic, region, e.getMessage());
                }
            }
        }

        long duration = (System.currentTimeMillis() - startTime) / 1000;
        log.info("=== Topic report generation complete: {} success, {} failed, {}s ===", success, failed, duration);

        // Also generate country analyses (47 countries)
        log.info("=== Starting country analysis generation ===");
        countryAnalysisGenerator.generateAllCountryAnalyses();
    }

    /**
     * Manual trigger for generating all reports (called from API).
     */
    public Map<String, Object> triggerGeneration() {
        // Run in background thread
        new Thread(() -> {
            try {
                generateAllReports();
            } catch (Exception e) {
                log.error("Manual report generation failed: {}", e.getMessage());
            }
        }, "report-generator").start();

        return Map.of(
            "status", "STARTED",
            "reports", TOPICS.size() * REGIONS.size(),
            "message", "Generating " + (TOPICS.size() * REGIONS.size()) + " reports in background"
        );
    }

    /**
     * Get a pre-generated report from Firestore.
     */
    public Map<String, Object> getPreGeneratedReport(String topic, String region) {
        String docId = topic + "_" + region;
        return firestoreService.getGeneratedReport(docId);
    }
}
