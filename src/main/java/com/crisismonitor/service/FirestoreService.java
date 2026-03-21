package com.crisismonitor.service;

import com.google.cloud.firestore.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Firestore Service for persistent data storage
 * Used for storing reports, user preferences, and historical data
 */
@Slf4j
@Service
public class FirestoreService {

    private final Firestore firestore;

    public FirestoreService(Firestore firestore) {
        this.firestore = firestore;
        if (firestore != null) {
            log.info("FirestoreService initialized with Firestore connection");
        } else {
            log.warn("FirestoreService initialized WITHOUT Firestore connection (disabled)");
        }
    }

    /**
     * Check if Firestore is available
     */
    public boolean isAvailable() {
        return firestore != null;
    }

    /**
     * Save a generated report to Firestore
     */
    public String saveReport(String topic, String region, int days, Map<String, Object> reportData) {
        if (!isAvailable()) {
            log.debug("Firestore not available, skipping report save");
            return null;
        }

        try {
            Map<String, Object> document = new HashMap<>();
            document.put("topic", topic);
            document.put("region", region);
            document.put("days", days);
            document.put("reportData", reportData);
            document.put("createdAt", LocalDateTime.now().toString());
            document.put("timestamp", System.currentTimeMillis());

            DocumentReference docRef = firestore.collection("reports").document();
            docRef.set(document).get();

            log.info("Report saved to Firestore: {}", docRef.getId());
            return docRef.getId();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to save report: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get recent reports for a topic/region combination
     */
    public List<Map<String, Object>> getRecentReports(String topic, String region, int limit) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }

        try {
            Query query = firestore.collection("reports")
                .whereEqualTo("topic", topic)
                .whereEqualTo("region", region)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit);

            List<Map<String, Object>> results = new ArrayList<>();
            for (QueryDocumentSnapshot doc : query.get().get().getDocuments()) {
                Map<String, Object> data = doc.getData();
                data.put("id", doc.getId());
                results.add(data);
            }
            return results;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to get reports: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Save a crisis alert
     */
    public String saveAlert(String iso3, String countryName, String alertType,
                           String severity, String description) {
        if (!isAvailable()) {
            return null;
        }

        try {
            Map<String, Object> alert = new HashMap<>();
            alert.put("iso3", iso3);
            alert.put("countryName", countryName);
            alert.put("alertType", alertType);
            alert.put("severity", severity);
            alert.put("description", description);
            alert.put("createdAt", LocalDateTime.now().toString());
            alert.put("timestamp", System.currentTimeMillis());
            alert.put("acknowledged", false);

            DocumentReference docRef = firestore.collection("alerts").document();
            docRef.set(alert).get();

            log.info("Alert saved: {} - {} ({})", countryName, alertType, severity);
            return docRef.getId();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to save alert: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get active (unacknowledged) alerts
     */
    public List<Map<String, Object>> getActiveAlerts(int limit) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }

        try {
            Query query = firestore.collection("alerts")
                .whereEqualTo("acknowledged", false)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit);

            List<Map<String, Object>> results = new ArrayList<>();
            for (QueryDocumentSnapshot doc : query.get().get().getDocuments()) {
                Map<String, Object> data = doc.getData();
                data.put("id", doc.getId());
                results.add(data);
            }
            return results;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to get alerts: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Save historical risk score for trend analysis
     */
    public void saveRiskScore(String iso3, String countryName, int score,
                              String riskLevel, List<String> drivers) {
        if (!isAvailable()) {
            return;
        }

        try {
            Map<String, Object> data = new HashMap<>();
            data.put("iso3", iso3);
            data.put("countryName", countryName);
            data.put("score", score);
            data.put("riskLevel", riskLevel);
            data.put("drivers", drivers);
            data.put("date", LocalDateTime.now().toLocalDate().toString());
            data.put("timestamp", System.currentTimeMillis());

            // Use date + iso3 as document ID for daily snapshots
            String docId = LocalDateTime.now().toLocalDate() + "_" + iso3;
            firestore.collection("riskScores").document(docId).set(data).get();

        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to save risk score: {}", e.getMessage());
        }
    }

    /**
     * Get risk score history for a country
     */
    public List<Map<String, Object>> getRiskScoreHistory(String iso3, int days) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }

        try {
            long cutoffTime = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);

            Query query = firestore.collection("riskScores")
                .whereEqualTo("iso3", iso3)
                .whereGreaterThan("timestamp", cutoffTime)
                .orderBy("timestamp", Query.Direction.ASCENDING);

            List<Map<String, Object>> results = new ArrayList<>();
            for (QueryDocumentSnapshot doc : query.get().get().getDocuments()) {
                results.add(doc.getData());
            }
            return results;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to get risk history: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==========================================
    // NOWCAST PERSISTENCE
    // ==========================================

    /**
     * Save daily food insecurity proxy for a country.
     * Used by NowcastService to build history for predictions.
     */
    public void saveProxyDaily(String iso3, String date, double proxy, Double fcs, Double rcsi) {
        if (!isAvailable()) return;
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("iso3", iso3);
            data.put("date", date);
            data.put("proxy", proxy);
            if (fcs != null) data.put("fcs", fcs);
            if (rcsi != null) data.put("rcsi", rcsi);
            data.put("timestamp", System.currentTimeMillis());

            String docId = date + "_" + iso3;
            firestore.collection("proxyHistory").document(docId).set(data).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to save proxy for {}: {}", iso3, e.getMessage());
        }
    }

    /**
     * Load proxy history for a country (last N days).
     */
    public List<Map<String, Object>> getProxyHistory(String iso3, int days) {
        if (!isAvailable()) return Collections.emptyList();
        try {
            long cutoffTime = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
            Query query = firestore.collection("proxyHistory")
                .whereEqualTo("iso3", iso3)
                .whereGreaterThan("timestamp", cutoffTime)
                .orderBy("timestamp", Query.Direction.ASCENDING);

            List<Map<String, Object>> results = new ArrayList<>();
            for (QueryDocumentSnapshot doc : query.get().get().getDocuments()) {
                results.add(doc.getData());
            }
            return results;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to get proxy history for {}: {}", iso3, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Load proxy history for ALL countries (last N days).
     */
    public List<Map<String, Object>> getAllProxyHistory(int days) {
        if (!isAvailable()) return Collections.emptyList();
        try {
            long cutoffTime = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
            Query query = firestore.collection("proxyHistory")
                .whereGreaterThan("timestamp", cutoffTime)
                .orderBy("timestamp", Query.Direction.ASCENDING);

            List<Map<String, Object>> results = new ArrayList<>();
            for (QueryDocumentSnapshot doc : query.get().get().getDocuments()) {
                results.add(doc.getData());
            }
            return results;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to get all proxy history: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Save a nowcast prediction for ground truthing.
     */
    public void saveNowcastPrediction(String iso3, String date, double currentProxy,
                                       double predictedChange, String trend, String confidence) {
        if (!isAvailable()) return;
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("iso3", iso3);
            data.put("date", date);
            data.put("currentProxy", currentProxy);
            data.put("predictedChange90d", predictedChange);
            data.put("trend", trend);
            data.put("confidence", confidence);
            data.put("timestamp", System.currentTimeMillis());

            String docId = date + "_" + iso3;
            firestore.collection("nowcastPredictions").document(docId).set(data).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to save nowcast for {}: {}", iso3, e.getMessage());
        }
    }

    // ==========================================
    // PRE-GENERATED INTELLIGENCE REPORTS
    // ==========================================

    /**
     * Save a pre-generated intelligence report.
     * Called by ReportSchedulerService on cron schedule.
     * Key format: "topic_region" (e.g., "conflict_mena")
     *
     * >>> CHANGE POINT: adjust schedule in ReportSchedulerService.java @Scheduled annotation
     * >>> Currently: weekly (Sunday 4:00 UTC). For production: every 12 hours
     */
    public void saveGeneratedReport(String docId, Map<String, Object> reportData) {
        if (!isAvailable()) return;
        try {
            firestore.collection("generatedReports").document(docId).set(reportData).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to save generated report {}: {}", docId, e.getMessage());
        }
    }

    /**
     * Get a pre-generated intelligence report by topic_region key.
     * Returns null if not found (report not yet generated).
     *
     * >>> CHANGE POINT: add paywall check here when credit system is implemented
     */
    public Map<String, Object> getGeneratedReport(String docId) {
        if (!isAvailable()) return null;
        try {
            var doc = firestore.collection("generatedReports").document(docId).get().get();
            if (doc.exists()) return doc.getData();
            return null;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to get generated report {}: {}", docId, e.getMessage());
            return null;
        }
    }

    /**
     * Generic document save
     */
    /**
     * Save document with specific ID (upsert — creates or updates).
     */
    public void saveDocument(String collection, String docId, Map<String, Object> data) {
        if (!isAvailable()) return;
        try {
            firestore.collection(collection).document(docId).set(data, com.google.cloud.firestore.SetOptions.merge()).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to save document {}/{}: {}", collection, docId, e.getMessage());
        }
    }

    /**
     * Save document with auto-generated ID.
     */
    public String saveDocument(String collection, Map<String, Object> data) {
        if (!isAvailable()) {
            return null;
        }

        try {
            data.put("createdAt", LocalDateTime.now().toString());
            data.put("timestamp", System.currentTimeMillis());

            DocumentReference docRef = firestore.collection(collection).document();
            docRef.set(data).get();
            return docRef.getId();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to save document: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generic document get
     */
    public Map<String, Object> getDocument(String collection, String documentId) {
        if (!isAvailable()) {
            return null;
        }

        try {
            DocumentSnapshot doc = firestore.collection(collection).document(documentId).get().get();
            if (doc.exists()) {
                Map<String, Object> data = doc.getData();
                if (data != null) {
                    data.put("id", doc.getId());
                }
                return data;
            }
            return null;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to get document: {}", e.getMessage());
            return null;
        }
    }
}
