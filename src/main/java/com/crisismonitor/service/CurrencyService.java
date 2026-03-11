package com.crisismonitor.service;

import com.crisismonitor.model.CurrencyData;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.*;

/**
 * Currency Service - Exchange rate monitoring for crisis detection
 *
 * Uses ExchangeRate-API (open access, no key required)
 * API: https://open.er-api.com/v6/latest/USD
 *
 * Currency devaluation is a leading indicator of economic crisis.
 */
@Slf4j
@Service
public class CurrencyService {

    private final WebClient exchangeClient;

    private static final String EXCHANGE_API = "https://open.er-api.com/v6/latest/USD";

    // Country -> Currency code mapping for crisis-prone countries (33 countries)
    private static final Map<String, String> COUNTRY_CURRENCY = Map.ofEntries(
            // Africa - Sahel & Horn
            Map.entry("SD", "SDG"),   // Sudanese Pound
            Map.entry("SS", "SSP"),   // South Sudanese Pound
            Map.entry("SO", "SOS"),   // Somali Shilling
            Map.entry("ET", "ETB"),   // Ethiopian Birr
            Map.entry("CD", "CDF"),   // Congolese Franc
            Map.entry("NG", "NGN"),   // Nigerian Naira
            Map.entry("ML", "XOF"),   // CFA Franc (West Africa)
            Map.entry("BF", "XOF"),   // CFA Franc (West Africa)
            Map.entry("NE", "XOF"),   // CFA Franc (West Africa)
            Map.entry("TD", "XAF"),   // CFA Franc (Central Africa)
            Map.entry("CF", "XAF"),   // CFA Franc (Central Africa)
            Map.entry("MZ", "MZN"),   // Mozambican Metical
            Map.entry("KE", "KES"),   // Kenyan Shilling
            Map.entry("UG", "UGX"),   // Ugandan Shilling
            Map.entry("ZW", "ZWL"),   // Zimbabwean Dollar
            // Middle East
            Map.entry("YE", "YER"),   // Yemeni Rial
            Map.entry("AF", "AFN"),   // Afghan Afghani
            Map.entry("IQ", "IQD"),   // Iraqi Dinar
            Map.entry("SY", "SYP"),   // Syrian Pound
            Map.entry("LB", "LBP"),   // Lebanese Pound
            // South Asia
            Map.entry("PK", "PKR"),   // Pakistani Rupee
            Map.entry("BD", "BDT"),   // Bangladeshi Taka
            Map.entry("IN", "INR"),   // Indian Rupee
            Map.entry("MM", "MMK"),   // Myanmar Kyat
            // Southeast Asia
            Map.entry("PH", "PHP"),   // Philippine Peso
            Map.entry("ID", "IDR"),   // Indonesian Rupiah
            Map.entry("VN", "VND"),   // Vietnamese Dong
            // Latin America (EC uses USD - no tracking)
            Map.entry("HT", "HTG"),   // Haitian Gourde
            Map.entry("PE", "PEN"),   // Peruvian Sol
            Map.entry("CO", "COP"),   // Colombian Peso
            Map.entry("GT", "GTQ"),   // Guatemalan Quetzal
            Map.entry("HN", "HNL")    // Honduran Lempira
    );

    private static final Map<String, String> COUNTRY_NAMES = Map.ofEntries(
            // Africa
            Map.entry("SD", "Sudan"), Map.entry("SS", "South Sudan"), Map.entry("SO", "Somalia"),
            Map.entry("ET", "Ethiopia"), Map.entry("CD", "DR Congo"), Map.entry("NG", "Nigeria"),
            Map.entry("ML", "Mali"), Map.entry("BF", "Burkina Faso"), Map.entry("NE", "Niger"),
            Map.entry("TD", "Chad"), Map.entry("CF", "CAR"), Map.entry("MZ", "Mozambique"),
            Map.entry("KE", "Kenya"), Map.entry("UG", "Uganda"), Map.entry("ZW", "Zimbabwe"),
            // Middle East
            Map.entry("YE", "Yemen"), Map.entry("AF", "Afghanistan"), Map.entry("IQ", "Iraq"),
            Map.entry("SY", "Syria"), Map.entry("LB", "Lebanon"),
            // South Asia
            Map.entry("PK", "Pakistan"), Map.entry("BD", "Bangladesh"), Map.entry("IN", "India"),
            Map.entry("MM", "Myanmar"),
            // Southeast Asia
            Map.entry("PH", "Philippines"), Map.entry("ID", "Indonesia"), Map.entry("VN", "Vietnam"),
            // Latin America
            Map.entry("HT", "Haiti"), Map.entry("PE", "Peru"), Map.entry("CO", "Colombia"),
            Map.entry("GT", "Guatemala"), Map.entry("HN", "Honduras")
    );

    // Historical baseline rates (approximate values for comparison)
    // These are rough baselines from ~90 days ago for change calculation
    // In production, you'd store historical data
    private static final Map<String, Double> BASELINE_RATES = Map.ofEntries(
            // Africa
            Map.entry("SDG", 480.0),    // Sudan
            Map.entry("SSP", 1050.0),   // South Sudan
            Map.entry("SOS", 570.0),    // Somalia
            Map.entry("ETB", 145.0),    // Ethiopia
            Map.entry("CDF", 2100.0),   // DR Congo
            Map.entry("NGN", 1500.0),   // Nigeria
            Map.entry("XOF", 600.0),    // CFA Franc West (ML, BF, NE)
            Map.entry("XAF", 600.0),    // CFA Franc Central (TD, CF)
            Map.entry("MZN", 63.0),     // Mozambique
            Map.entry("KES", 153.0),    // Kenya
            Map.entry("UGX", 3700.0),   // Uganda
            Map.entry("ZWL", 13000.0),  // Zimbabwe
            // Middle East
            Map.entry("YER", 250.0),    // Yemen
            Map.entry("AFN", 68.0),     // Afghanistan
            Map.entry("IQD", 1310.0),   // Iraq
            Map.entry("SYP", 13000.0),  // Syria (black market rate)
            Map.entry("LBP", 89000.0),  // Lebanon
            // South Asia
            Map.entry("PKR", 278.0),    // Pakistan
            Map.entry("BDT", 120.0),    // Bangladesh
            Map.entry("INR", 83.0),     // India
            Map.entry("MMK", 2100.0),   // Myanmar
            // Southeast Asia
            Map.entry("PHP", 56.0),     // Philippines
            Map.entry("IDR", 15600.0),  // Indonesia
            Map.entry("VND", 24500.0),  // Vietnam
            // Latin America
            Map.entry("HTG", 130.0),    // Haiti
            Map.entry("PEN", 3.7),      // Peru
            Map.entry("COP", 4000.0),   // Colombia
            Map.entry("GTQ", 7.8),      // Guatemala
            Map.entry("HNL", 24.7)      // Honduras
    );

    public CurrencyService() {
        this.exchangeClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    /**
     * Get current exchange rates from API
     */
    @Cacheable("exchangeRates")
    public Map<String, Double> getCurrentRates() {
        log.info("Fetching current exchange rates from ExchangeRate-API");

        try {
            JsonNode response = exchangeClient.get()
                    .uri(EXCHANGE_API)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null && "success".equals(response.path("result").asText())) {
                Map<String, Double> rates = new HashMap<>();
                JsonNode ratesNode = response.get("rates");

                for (String currencyCode : COUNTRY_CURRENCY.values()) {
                    if (ratesNode.has(currencyCode)) {
                        rates.put(currencyCode, ratesNode.get(currencyCode).asDouble());
                    }
                }

                log.info("Got exchange rates for {} currencies", rates.size());
                return rates;
            }

        } catch (Exception e) {
            log.error("Error fetching exchange rates: {}", e.getMessage());
        }

        return Collections.emptyMap();
    }

    /**
     * Get currency data for a specific country
     */
    @Cacheable(value = "currencyData", key = "#iso2")
    public CurrencyData getCurrencyData(String iso2) {
        String currencyCode = COUNTRY_CURRENCY.get(iso2);
        if (currencyCode == null) {
            return null;
        }

        Map<String, Double> rates = getCurrentRates();
        Double currentRate = rates.get(currencyCode);

        if (currentRate == null) {
            return null;
        }

        Double baselineRate = BASELINE_RATES.get(currencyCode);
        Double change = null;
        String trend = "UNKNOWN";
        int riskScore = 0;

        if (baselineRate != null && baselineRate > 0) {
            // Calculate % change (higher rate = weaker currency)
            change = ((currentRate - baselineRate) / baselineRate) * 100;

            // Determine trend
            if (change > 30) {
                trend = "CRISIS";
                riskScore = 100;
            } else if (change > 15) {
                trend = "DEVALUING";
                riskScore = 75;
            } else if (change > 5) {
                trend = "WEAKENING";
                riskScore = 50;
            } else if (change > -5) {
                trend = "STABLE";
                riskScore = 10;
            } else {
                trend = "STRENGTHENING";
                riskScore = 5;
            }
        }

        return CurrencyData.builder()
                .iso2(iso2)
                .countryName(COUNTRY_NAMES.get(iso2))
                .currencyCode(currencyCode)
                .currentRate(Math.round(currentRate * 100) / 100.0)
                .rate30dAgo(baselineRate)
                .change30d(change != null ? Math.round(change * 10) / 10.0 : null)
                .trend(trend)
                .riskScore(riskScore)
                .dataDate(LocalDate.now())
                .build();
    }

    /**
     * Get currency data for all monitored countries
     */
    @Cacheable("allCurrencyData")
    public List<CurrencyData> getAllCurrencyData() {
        log.info("Fetching currency data for all monitored countries");

        List<CurrencyData> result = new ArrayList<>();

        for (String iso2 : COUNTRY_CURRENCY.keySet()) {
            try {
                CurrencyData data = getCurrencyData(iso2);
                if (data != null) {
                    result.add(data);
                }
            } catch (Exception e) {
                log.warn("Error getting currency for {}: {}", iso2, e.getMessage());
            }
        }

        // Sort by risk score descending
        result.sort((a, b) -> Integer.compare(b.getRiskScore(), a.getRiskScore()));

        log.info("Got currency data for {} countries", result.size());
        return result;
    }

    /**
     * Get countries with significant currency devaluation
     */
    public List<CurrencyData> getDevaluingCurrencies() {
        return getAllCurrencyData().stream()
                .filter(CurrencyData::isDevaluing)
                .toList();
    }

    /**
     * Get countries in currency crisis (>30% devaluation)
     */
    public List<CurrencyData> getCurrencyCrisis() {
        return getAllCurrencyData().stream()
                .filter(c -> c.getChange30d() != null && c.getChange30d() > 30)
                .toList();
    }

    public Set<String> getMonitoredCountries() {
        return COUNTRY_CURRENCY.keySet();
    }
}
