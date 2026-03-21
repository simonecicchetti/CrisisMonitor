package com.crisismonitor.service;

import com.crisismonitor.model.MobilityStock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for fetching IOM Displacement Tracking Matrix (DTM) data via HDX
 * Data source: https://data.humdata.org/dataset/global-iom-dtm-from-api
 *
 * Falls back to IDMC GRID 2025 static data (end-of-2024 figures) when
 * HDX is unreachable (e.g., Cloud Run IP blocking).
 */
@Slf4j
@Service
public class DTMService {

    private final WebClient hdxClient;

    private static final String DTM_CSV_URL =
            "https://data.humdata.org/dataset/32d0365c-d513-4721-8d66-1b19b12c4b08/resource/80911e9b-7527-469a-a545-4074860e1288/download/global-iom-dtm-from-api-admin-0-to-2.csv";

    public DTMService() {
        // Configure HttpClient to follow redirects
        HttpClient httpClient = HttpClient.create()
                .followRedirect(true);

        this.hdxClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(50 * 1024 * 1024)) // 50MB for large CSV
                .build();
    }

    /**
     * Get latest IDP figures aggregated at country level (Admin0)
     *
     * Algorithm: For each country, find the most recent reporting date,
     * then sum all Admin0 records at that date (different regions/reasons).
     */
    @Cacheable("dtmCountryData")
    public List<MobilityStock> getCountryLevelIdps() {
        log.info("Fetching IOM DTM data from HDX...");

        try {
            String csvContent = hdxClient.get()
                    .uri(DTM_CSV_URL)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .block();

            if (csvContent == null || csvContent.isEmpty()) {
                log.error("Empty response from HDX DTM CSV");
                return Collections.emptyList();
            }

            // Parse CSV
            List<DTMRecord> records = parseCSV(csvContent);
            log.info("Parsed {} DTM records", records.size());

            // Filter to Admin0 records with valid IDP counts
            List<DTMRecord> admin0Records = records.stream()
                    .filter(r -> "0".equals(r.adminLevel) && r.idpCount != null && r.idpCount > 0)
                    .collect(Collectors.toList());

            // Group by country
            Map<String, List<DTMRecord>> byCountry = admin0Records.stream()
                    .filter(r -> r.iso3 != null)
                    .collect(Collectors.groupingBy(r -> r.iso3));

            List<MobilityStock> stocks = new ArrayList<>();

            for (Map.Entry<String, List<DTMRecord>> entry : byCountry.entrySet()) {
                String iso3 = entry.getKey();
                List<DTMRecord> countryRecords = entry.getValue();

                // Find the latest reporting date for this country
                LocalDate latestDate = countryRecords.stream()
                        .map(r -> r.reportingDate)
                        .filter(Objects::nonNull)
                        .max(LocalDate::compareTo)
                        .orElse(null);

                if (latestDate == null) continue;

                // Sum all records at the latest date
                long totalIdps = countryRecords.stream()
                        .filter(r -> latestDate.equals(r.reportingDate))
                        .mapToLong(r -> r.idpCount != null ? r.idpCount : 0)
                        .sum();

                // Get country name from first record
                String countryName = countryRecords.stream()
                        .filter(r -> r.countryName != null)
                        .findFirst()
                        .map(r -> r.countryName)
                        .orElse(iso3);

                stocks.add(MobilityStock.builder()
                        .iso3(iso3)
                        .countryName(countryName)
                        .idps(totalIdps)
                        .year(latestDate.getYear())
                        .source("IOM DTM")
                        .adminLevel("ADMIN0")
                        .build());
            }

            // Sort by IDPs descending
            stocks.sort((a, b) -> Long.compare(
                    b.getIdps() != null ? b.getIdps() : 0,
                    a.getIdps() != null ? a.getIdps() : 0
            ));

            log.info("Aggregated DTM data for {} countries", stocks.size());
            return stocks;

        } catch (Exception e) {
            log.warn("Live DTM fetch failed ({}), using IDMC GRID 2025 static data", e.getMessage());
            return getStaticIdpData();
        }
    }

    /**
     * Get IDP data by displacement reason
     */
    @Cacheable("dtmByReason")
    public Map<String, Long> getIdpsByReason() {
        log.info("Fetching DTM data by displacement reason...");

        try {
            String csvContent = hdxClient.get()
                    .uri(DTM_CSV_URL)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .block();

            if (csvContent == null) return Collections.emptyMap();

            List<DTMRecord> records = parseCSV(csvContent);

            // Aggregate by reason
            Map<String, Long> byReason = records.stream()
                    .filter(r -> r.displacementReason != null && r.idpCount != null)
                    .collect(Collectors.groupingBy(
                            r -> normalizeReason(r.displacementReason),
                            Collectors.summingLong(r -> r.idpCount)
                    ));

            log.info("Aggregated IDPs by {} reasons", byReason.size());
            return byReason;

        } catch (Exception e) {
            log.error("Error fetching DTM by reason: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Get list of active DTM operations
     */
    @Cacheable("dtmOperations")
    public List<Map<String, Object>> getActiveOperations() {
        log.info("Fetching active DTM operations...");

        try {
            String csvContent = hdxClient.get()
                    .uri(DTM_CSV_URL)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .block();

            if (csvContent == null) return Collections.emptyList();

            List<DTMRecord> records = parseCSV(csvContent);

            // Get unique operations with latest data
            Map<String, DTMRecord> operationMap = new HashMap<>();
            for (DTMRecord record : records) {
                if (record.operation == null || !"Active".equalsIgnoreCase(record.operationStatus)) {
                    continue;
                }
                String key = record.operation;
                DTMRecord existing = operationMap.get(key);
                if (existing == null ||
                        (record.reportingDate != null && existing.reportingDate != null &&
                                record.reportingDate.isAfter(existing.reportingDate))) {
                    operationMap.put(key, record);
                }
            }

            return operationMap.values().stream()
                    .map(r -> {
                        Map<String, Object> op = new LinkedHashMap<>();
                        op.put("operation", r.operation);
                        op.put("country", r.countryName);
                        op.put("iso3", r.iso3);
                        op.put("latestReport", r.reportingDate != null ? r.reportingDate.toString() : null);
                        op.put("status", r.operationStatus);
                        return op;
                    })
                    .sorted((a, b) -> String.valueOf(a.get("country"))
                            .compareTo(String.valueOf(b.get("country"))))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error fetching DTM operations: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<DTMRecord> parseCSV(String csv) {
        List<DTMRecord> records = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return records;

            // Skip HXL tag line if present
            String firstDataLine = reader.readLine();
            if (firstDataLine != null && firstDataLine.startsWith("#")) {
                firstDataLine = reader.readLine();
            }

            String[] headers = headerLine.split(",");
            Map<String, Integer> headerIndex = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                headerIndex.put(headers[i].trim(), i);
            }

            String line = firstDataLine;
            while (line != null) {
                try {
                    String[] values = parseCSVLine(line);
                    if (values.length >= headers.length) {
                        DTMRecord record = new DTMRecord();
                        record.operation = getValue(values, headerIndex, "operation");
                        record.countryName = getValue(values, headerIndex, "admin0Name");
                        record.iso3 = getValue(values, headerIndex, "admin0Pcode");
                        record.adminLevel = getValue(values, headerIndex, "adminLevel");
                        record.displacementReason = getValue(values, headerIndex, "displacementReason");
                        record.operationStatus = getValue(values, headerIndex, "operationStatus");

                        String idpStr = getValue(values, headerIndex, "numPresentIdpInd");
                        if (idpStr != null && !idpStr.isEmpty()) {
                            try {
                                record.idpCount = Long.parseLong(idpStr);
                            } catch (NumberFormatException ignored) {}
                        }

                        String dateStr = getValue(values, headerIndex, "reportingDate");
                        if (dateStr != null && dateStr.contains("T")) {
                            try {
                                record.reportingDate = LocalDate.parse(dateStr.split("T")[0]);
                            } catch (Exception ignored) {}
                        }

                        records.add(record);
                    }
                } catch (Exception e) {
                    // Skip malformed lines
                }
                line = reader.readLine();
            }
        } catch (Exception e) {
            log.error("Error parsing CSV: {}", e.getMessage());
        }
        return records;
    }

    private String[] parseCSVLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        result.add(current.toString().trim());
        return result.toArray(new String[0]);
    }

    private String getValue(String[] values, Map<String, Integer> index, String header) {
        Integer idx = index.get(header);
        if (idx != null && idx < values.length) {
            return values[idx].trim();
        }
        return null;
    }

    private String normalizeReason(String reason) {
        if (reason == null) return "Unknown";
        if (reason.toLowerCase().contains("conflict")) return "Conflict";
        if (reason.toLowerCase().contains("disaster") || reason.toLowerCase().contains("natural")) return "Natural Disaster";
        if (reason.toLowerCase().contains("development")) return "Development";
        if (reason.toLowerCase().contains("no reason")) return "Unknown";
        return reason;
    }

    /**
     * Static IDP data from IDMC GRID 2025 report (end-of-2024 figures).
     * Used as fallback when HDX CSV is unreachable (Cloud Run IP blocking).
     * Source: https://www.internal-displacement.org/global-report/grid2025/
     */
    private List<MobilityStock> getStaticIdpData() {
        log.info("Loading static IDP data (IDMC GRID 2025, end-of-2024 figures)");
        List<MobilityStock> stocks = new ArrayList<>();

        // Top 20 countries by IDP count (conflict + disaster), IDMC GRID 2025
        addStatic(stocks, "SDN", "Sudan", 11_800_000);
        addStatic(stocks, "SYR", "Syria", 7_400_000);
        addStatic(stocks, "COL", "Colombia", 7_400_000);
        addStatic(stocks, "COD", "DR Congo", 7_000_000);
        addStatic(stocks, "YEM", "Yemen", 5_300_000);
        addStatic(stocks, "AFG", "Afghanistan", 5_200_000);
        addStatic(stocks, "NGA", "Nigeria", 4_600_000);
        addStatic(stocks, "SOM", "Somalia", 3_900_000);
        addStatic(stocks, "MMR", "Myanmar", 4_000_000);
        addStatic(stocks, "UKR", "Ukraine", 3_700_000);
        addStatic(stocks, "ETH", "Ethiopia", 2_850_000);
        addStatic(stocks, "BFA", "Burkina Faso", 2_030_000);
        addStatic(stocks, "IRQ", "Iraq", 1_120_000);
        addStatic(stocks, "SSD", "South Sudan", 1_120_000);
        addStatic(stocks, "CMR", "Cameroon", 1_030_000);
        addStatic(stocks, "MOZ", "Mozambique", 940_000);
        addStatic(stocks, "PAK", "Pakistan", 800_000);
        addStatic(stocks, "HTI", "Haiti", 700_000);
        addStatic(stocks, "IND", "India", 630_000);
        addStatic(stocks, "PHL", "Philippines", 610_000);

        // Already sorted descending by construction
        return stocks;
    }

    private void addStatic(List<MobilityStock> list, String iso3, String name, long idps) {
        list.add(MobilityStock.builder()
                .iso3(iso3)
                .countryName(name)
                .idps(idps)
                .year(2024)
                .source("IDMC GRID 2025")
                .adminLevel("ADMIN0")
                .build());
    }

    // Internal record class for parsing
    private static class DTMRecord {
        String operation;
        String countryName;
        String iso3;
        String adminLevel;
        Long idpCount;
        LocalDate reportingDate;
        String displacementReason;
        String operationStatus;
    }
}
