package com.crisismonitor.service;

import com.crisismonitor.model.RiskScore;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Regional Cluster Alerts - WFP-style regional aggregation
 *
 * Groups countries by geographic cluster and identifies
 * regional convergence patterns (e.g., "Horn of Africa heating up")
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegionalClusterService {

    // Static country → cluster mapping (14 regions, 60+ countries)
    private static final Map<String, String> COUNTRY_TO_CLUSTER = new HashMap<>();
    static {
        // Horn of Africa
        COUNTRY_TO_CLUSTER.put("ETH", "Horn of Africa");
        COUNTRY_TO_CLUSTER.put("SOM", "Horn of Africa");
        COUNTRY_TO_CLUSTER.put("SDN", "Horn of Africa");
        COUNTRY_TO_CLUSTER.put("SSD", "Horn of Africa");
        COUNTRY_TO_CLUSTER.put("KEN", "Horn of Africa");
        COUNTRY_TO_CLUSTER.put("UGA", "Horn of Africa");
        COUNTRY_TO_CLUSTER.put("ERI", "Horn of Africa");
        COUNTRY_TO_CLUSTER.put("DJI", "Horn of Africa");

        // Sahel
        COUNTRY_TO_CLUSTER.put("MLI", "Sahel");
        COUNTRY_TO_CLUSTER.put("BFA", "Sahel");
        COUNTRY_TO_CLUSTER.put("NER", "Sahel");
        COUNTRY_TO_CLUSTER.put("TCD", "Sahel");
        COUNTRY_TO_CLUSTER.put("NGA", "Sahel");
        COUNTRY_TO_CLUSTER.put("CAF", "Sahel");
        COUNTRY_TO_CLUSTER.put("MRT", "Sahel");

        // West Africa (coastal)
        COUNTRY_TO_CLUSTER.put("SEN", "West Africa");
        COUNTRY_TO_CLUSTER.put("GIN", "West Africa");
        COUNTRY_TO_CLUSTER.put("SLE", "West Africa");
        COUNTRY_TO_CLUSTER.put("LBR", "West Africa");
        COUNTRY_TO_CLUSTER.put("CIV", "West Africa");
        COUNTRY_TO_CLUSTER.put("GHA", "West Africa");
        COUNTRY_TO_CLUSTER.put("TGO", "West Africa");
        COUNTRY_TO_CLUSTER.put("BEN", "West Africa");
        COUNTRY_TO_CLUSTER.put("GNB", "West Africa");
        COUNTRY_TO_CLUSTER.put("GMB", "West Africa");

        // Great Lakes
        COUNTRY_TO_CLUSTER.put("COD", "Great Lakes");
        COUNTRY_TO_CLUSTER.put("RWA", "Great Lakes");
        COUNTRY_TO_CLUSTER.put("BDI", "Great Lakes");
        COUNTRY_TO_CLUSTER.put("TZA", "Great Lakes");

        // Southern Africa
        COUNTRY_TO_CLUSTER.put("MOZ", "Southern Africa");
        COUNTRY_TO_CLUSTER.put("ZWE", "Southern Africa");
        COUNTRY_TO_CLUSTER.put("MWI", "Southern Africa");
        COUNTRY_TO_CLUSTER.put("ZMB", "Southern Africa");
        COUNTRY_TO_CLUSTER.put("AGO", "Southern Africa");
        COUNTRY_TO_CLUSTER.put("NAM", "Southern Africa");
        COUNTRY_TO_CLUSTER.put("BWA", "Southern Africa");
        COUNTRY_TO_CLUSTER.put("LSO", "Southern Africa");
        COUNTRY_TO_CLUSTER.put("SWZ", "Southern Africa");
        COUNTRY_TO_CLUSTER.put("MDG", "Southern Africa");

        // North Africa
        COUNTRY_TO_CLUSTER.put("LBY", "North Africa");
        COUNTRY_TO_CLUSTER.put("EGY", "North Africa");
        COUNTRY_TO_CLUSTER.put("TUN", "North Africa");
        COUNTRY_TO_CLUSTER.put("DZA", "North Africa");
        COUNTRY_TO_CLUSTER.put("MAR", "North Africa");

        // Middle East
        COUNTRY_TO_CLUSTER.put("YEM", "Middle East");
        COUNTRY_TO_CLUSTER.put("SYR", "Middle East");
        COUNTRY_TO_CLUSTER.put("IRQ", "Middle East");
        COUNTRY_TO_CLUSTER.put("LBN", "Middle East");
        COUNTRY_TO_CLUSTER.put("PSE", "Middle East");
        COUNTRY_TO_CLUSTER.put("JOR", "Middle East");

        // South Asia
        COUNTRY_TO_CLUSTER.put("AFG", "South Asia");
        COUNTRY_TO_CLUSTER.put("PAK", "South Asia");
        COUNTRY_TO_CLUSTER.put("BGD", "South Asia");
        COUNTRY_TO_CLUSTER.put("MMR", "South Asia");
        COUNTRY_TO_CLUSTER.put("IND", "South Asia");
        COUNTRY_TO_CLUSTER.put("NPL", "South Asia");
        COUNTRY_TO_CLUSTER.put("LKA", "South Asia");

        // Central Asia
        COUNTRY_TO_CLUSTER.put("TJK", "Central Asia");
        COUNTRY_TO_CLUSTER.put("KGZ", "Central Asia");
        COUNTRY_TO_CLUSTER.put("UZB", "Central Asia");
        COUNTRY_TO_CLUSTER.put("TKM", "Central Asia");

        // Southeast Asia
        COUNTRY_TO_CLUSTER.put("PHL", "Southeast Asia");
        COUNTRY_TO_CLUSTER.put("IDN", "Southeast Asia");
        COUNTRY_TO_CLUSTER.put("VNM", "Southeast Asia");
        COUNTRY_TO_CLUSTER.put("TLS", "Southeast Asia");
        COUNTRY_TO_CLUSTER.put("KHM", "Southeast Asia");
        COUNTRY_TO_CLUSTER.put("LAO", "Southeast Asia");

        // Eastern Europe
        COUNTRY_TO_CLUSTER.put("UKR", "Eastern Europe");
        COUNTRY_TO_CLUSTER.put("MDA", "Eastern Europe");

        // Central America
        COUNTRY_TO_CLUSTER.put("GTM", "Central America");
        COUNTRY_TO_CLUSTER.put("HND", "Central America");
        COUNTRY_TO_CLUSTER.put("SLV", "Central America");
        COUNTRY_TO_CLUSTER.put("NIC", "Central America");
        COUNTRY_TO_CLUSTER.put("PAN", "Central America");
        COUNTRY_TO_CLUSTER.put("CRI", "Central America");

        // Northern Triangle (migration corridor - overlaps with Central America for special alerts)
        // These countries are also in Central America but we track them separately for migration analysis

        // Caribbean
        COUNTRY_TO_CLUSTER.put("HTI", "Caribbean");
        COUNTRY_TO_CLUSTER.put("CUB", "Caribbean");
        COUNTRY_TO_CLUSTER.put("DOM", "Caribbean");
        COUNTRY_TO_CLUSTER.put("JAM", "Caribbean");

        // South America
        COUNTRY_TO_CLUSTER.put("COL", "South America");
        COUNTRY_TO_CLUSTER.put("VEN", "South America");
        COUNTRY_TO_CLUSTER.put("ECU", "South America");
        COUNTRY_TO_CLUSTER.put("PER", "South America");
        COUNTRY_TO_CLUSTER.put("BOL", "South America");
        COUNTRY_TO_CLUSTER.put("BRA", "South America");
        COUNTRY_TO_CLUSTER.put("ARG", "South America");
    }

    /**
     * Analyze regional clusters from current risk scores
     */
    public List<ClusterAlert> analyzeRegionalClusters(List<RiskScore> riskScores) {
        if (riskScores == null || riskScores.isEmpty()) {
            return Collections.emptyList();
        }

        // Group countries by cluster
        Map<String, List<RiskScore>> byCluster = riskScores.stream()
                .filter(s -> s.getIso3() != null && COUNTRY_TO_CLUSTER.containsKey(s.getIso3()))
                .collect(Collectors.groupingBy(s -> COUNTRY_TO_CLUSTER.get(s.getIso3())));

        List<ClusterAlert> alerts = new ArrayList<>();

        for (Map.Entry<String, List<RiskScore>> entry : byCluster.entrySet()) {
            String clusterName = entry.getKey();
            List<RiskScore> members = entry.getValue();

            ClusterAlert alert = analyzeCluster(clusterName, members);
            if (alert != null && alert.isSignificant()) {
                alerts.add(alert);
            }
        }

        // Sort by severity (most critical first)
        alerts.sort((a, b) -> Integer.compare(b.getSeverityRank(), a.getSeverityRank()));

        return alerts.stream().limit(3).collect(Collectors.toList()); // Top 3 clusters
    }

    private ClusterAlert analyzeCluster(String clusterName, List<RiskScore> members) {
        int criticalCount = 0;
        int alertCount = 0;
        int warningCount = 0;
        int risingCount = 0;

        Map<String, Integer> driverCounts = new HashMap<>();
        List<String> criticalCountries = new ArrayList<>();
        List<String> alertCountries = new ArrayList<>();

        for (RiskScore score : members) {
            String level = score.getRiskLevel();

            if ("CRITICAL".equals(level)) {
                criticalCount++;
                criticalCountries.add(score.getCountryName());
            } else if ("ALERT".equals(level)) {
                alertCount++;
                alertCountries.add(score.getCountryName());
            } else if ("WARNING".equals(level)) {
                warningCount++;
            }

            // Track rising trends
            if ("rising".equals(score.getTrend())) {
                risingCount++;
            }

            // Count drivers
            if (score.getDrivers() != null) {
                for (String driver : score.getDrivers()) {
                    driverCounts.merge(driver, 1, Integer::sum);
                }
            }
        }

        // Find top drivers (those appearing in 2+ countries)
        List<String> topDrivers = driverCounts.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .limit(2)
                .collect(Collectors.toList());

        // Determine cluster status
        String status;
        String statusIcon;
        int severityRank;

        if (criticalCount >= 2 || (criticalCount >= 1 && alertCount >= 2)) {
            status = "CRITICAL CLUSTER";
            statusIcon = "🔴";
            severityRank = 3;
        } else if (alertCount >= 2 || (criticalCount >= 1 && alertCount >= 1)) {
            status = "HIGH RISK CLUSTER";
            statusIcon = "🟠";
            severityRank = 2;
        } else if (warningCount >= 2 || risingCount >= 2) {
            status = "DETERIORATING";
            statusIcon = "🟡";
            severityRank = 1;
        } else {
            return null; // Not significant
        }

        // Build description based on severity
        StringBuilder desc = new StringBuilder();
        int highCount = criticalCount + alertCount;

        if (highCount > 0) {
            desc.append(highCount).append(" countries ALERT/CRITICAL");
        } else if (warningCount > 0) {
            desc.append(warningCount).append(" countries elevated risk");
        } else if (risingCount > 0) {
            desc.append(risingCount).append(" countries trending up");
        }

        if (!topDrivers.isEmpty()) {
            desc.append(" (").append(String.join("+", topDrivers)).append(" convergence)");
        }

        // Build affected countries string
        List<String> affectedCountries = new ArrayList<>();
        affectedCountries.addAll(criticalCountries);
        affectedCountries.addAll(alertCountries);

        return ClusterAlert.builder()
                .clusterName(clusterName)
                .status(status)
                .statusIcon(statusIcon)
                .description(desc.toString())
                .criticalCount(criticalCount)
                .alertCount(alertCount)
                .warningCount(warningCount)
                .risingCount(risingCount)
                .topDrivers(topDrivers)
                .affectedCountries(affectedCountries.stream().limit(3).collect(Collectors.toList()))
                .severityRank(severityRank)
                .totalMembers(members.size())
                .build();
    }

    /**
     * Get a single-line summary for AI prompts
     */
    public String getClusterSummaryForAI(List<ClusterAlert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return "No significant regional cluster alerts.";
        }

        return alerts.stream()
                .map(a -> String.format("%s: %s - %s", a.getClusterName(), a.getStatus(), a.getDescription()))
                .collect(Collectors.joining("; "));
    }

    @Data
    @lombok.Builder
    public static class ClusterAlert {
        private String clusterName;
        private String status;          // CRITICAL CLUSTER, HIGH RISK CLUSTER, DETERIORATING
        private String statusIcon;
        private String description;
        private int criticalCount;
        private int alertCount;
        private int warningCount;
        private int risingCount;
        private List<String> topDrivers;
        private List<String> affectedCountries;
        private int severityRank;       // 3=critical, 2=high, 1=deteriorating
        private int totalMembers;

        public boolean isSignificant() {
            return severityRank > 0;
        }
    }
}
