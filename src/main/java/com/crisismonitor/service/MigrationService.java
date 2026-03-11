package com.crisismonitor.service;

import com.crisismonitor.model.MigrationData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MigrationService {

    private static final String DARIEN_NATIONALITY_URL =
            "https://rstudio.unhcr.org/ibc_data/pan/darien/nationality.csv";

    @Cacheable("migrationData")
    public List<MigrationData> getDarienMigrationData() {
        log.info("Fetching Darien migration data from UNHCR...");

        try {
            URL url = new URL(DARIEN_NATIONALITY_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            List<MigrationData> result = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {

                String line;
                boolean firstLine = true;

                while ((line = reader.readLine()) != null) {
                    if (firstLine) {
                        firstLine = false;
                        continue; // Skip header
                    }

                    String[] parts = line.split(",");
                    if (parts.length >= 5) {
                        try {
                            MigrationData data = MigrationData.builder()
                                    .nationality(parts[0])
                                    .date(LocalDate.parse(parts[1]))
                                    .count(Long.parseLong(parts[2]))
                                    .iso3(parts[3])
                                    .countryName(parts[4])
                                    .build();
                            result.add(data);
                        } catch (Exception e) {
                            log.debug("Skipping malformed line: {}", line);
                        }
                    }
                }
            }

            log.info("Fetched {} migration records", result.size());
            return result;

        } catch (Exception e) {
            log.error("Error fetching migration data: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get recent migration data (last 12 months)
     */
    public List<MigrationData> getRecentMigrationData() {
        LocalDate cutoff = LocalDate.now().minusMonths(12);
        return getDarienMigrationData().stream()
                .filter(m -> m.getDate().isAfter(cutoff))
                .sorted(Comparator.comparing(MigrationData::getDate).reversed())
                .toList();
    }

    /**
     * Get migration totals by country for a given period
     */
    public Map<String, Long> getMigrationByCountry(int monthsBack) {
        LocalDate cutoff = LocalDate.now().minusMonths(monthsBack);
        return getDarienMigrationData().stream()
                .filter(m -> m.getDate().isAfter(cutoff))
                .collect(Collectors.groupingBy(
                        MigrationData::getCountryName,
                        Collectors.summingLong(MigrationData::getCount)
                ));
    }

    /**
     * Get monthly totals (all nationalities combined)
     */
    public Map<String, Long> getMonthlyTotals(int monthsBack) {
        LocalDate cutoff = LocalDate.now().minusMonths(monthsBack);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM");

        return getDarienMigrationData().stream()
                .filter(m -> m.getDate().isAfter(cutoff))
                .collect(Collectors.groupingBy(
                        m -> m.getDate().format(formatter),
                        TreeMap::new,
                        Collectors.summingLong(MigrationData::getCount)
                ));
    }
}
