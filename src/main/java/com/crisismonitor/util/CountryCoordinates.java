package com.crisismonitor.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Static lookup table for country centroid coordinates
 */
public class CountryCoordinates {

    private static final Map<String, double[]> COORDINATES = new HashMap<>();

    static {
        // Africa
        COORDINATES.put("AFG", new double[]{33.93, 67.71});    // Afghanistan
        COORDINATES.put("AGO", new double[]{-11.20, 17.87});   // Angola
        COORDINATES.put("BDI", new double[]{-3.37, 29.92});    // Burundi
        COORDINATES.put("BEN", new double[]{9.31, 2.32});      // Benin
        COORDINATES.put("BFA", new double[]{12.24, -1.56});    // Burkina Faso
        COORDINATES.put("BWA", new double[]{-22.33, 24.68});   // Botswana
        COORDINATES.put("CAF", new double[]{6.61, 20.94});     // Central African Republic
        COORDINATES.put("CIV", new double[]{7.54, -5.55});     // Cote d'Ivoire
        COORDINATES.put("CMR", new double[]{7.37, 12.35});     // Cameroon
        COORDINATES.put("COD", new double[]{-4.04, 21.76});    // DR Congo
        COORDINATES.put("COG", new double[]{-0.23, 15.83});    // Congo
        COORDINATES.put("DJI", new double[]{11.83, 42.59});    // Djibouti
        COORDINATES.put("EGY", new double[]{26.82, 30.80});    // Egypt
        COORDINATES.put("ERI", new double[]{15.18, 39.78});    // Eritrea
        COORDINATES.put("ETH", new double[]{9.15, 40.49});     // Ethiopia
        COORDINATES.put("GAB", new double[]{-0.80, 11.61});    // Gabon
        COORDINATES.put("GHA", new double[]{7.95, -1.02});     // Ghana
        COORDINATES.put("GIN", new double[]{9.95, -9.70});     // Guinea
        COORDINATES.put("GMB", new double[]{13.44, -15.31});   // Gambia
        COORDINATES.put("GNB", new double[]{11.80, -15.18});   // Guinea-Bissau
        COORDINATES.put("GNQ", new double[]{1.65, 10.27});     // Equatorial Guinea
        COORDINATES.put("KEN", new double[]{-0.02, 37.91});    // Kenya
        COORDINATES.put("LBR", new double[]{6.43, -9.43});     // Liberia
        COORDINATES.put("LBY", new double[]{26.34, 17.23});    // Libya
        COORDINATES.put("LSO", new double[]{-29.61, 28.23});   // Lesotho
        COORDINATES.put("MAR", new double[]{31.79, -7.09});    // Morocco
        COORDINATES.put("MDG", new double[]{-18.77, 46.87});   // Madagascar
        COORDINATES.put("MLI", new double[]{17.57, -4.00});    // Mali
        COORDINATES.put("MOZ", new double[]{-18.67, 35.53});   // Mozambique
        COORDINATES.put("MRT", new double[]{21.01, -10.94});   // Mauritania
        COORDINATES.put("MWI", new double[]{-13.25, 34.30});   // Malawi
        COORDINATES.put("NAM", new double[]{-22.96, 18.49});   // Namibia
        COORDINATES.put("NER", new double[]{17.61, 8.08});     // Niger
        COORDINATES.put("NGA", new double[]{9.08, 8.68});      // Nigeria
        COORDINATES.put("RWA", new double[]{-1.94, 29.87});    // Rwanda
        COORDINATES.put("SDN", new double[]{15.50, 32.53});    // Sudan
        COORDINATES.put("SEN", new double[]{14.50, -14.45});   // Senegal
        COORDINATES.put("SLE", new double[]{8.46, -11.78});    // Sierra Leone
        COORDINATES.put("SOM", new double[]{5.15, 46.20});     // Somalia
        COORDINATES.put("SSD", new double[]{6.88, 31.31});     // South Sudan
        COORDINATES.put("SWZ", new double[]{-26.52, 31.47});   // Eswatini
        COORDINATES.put("TCD", new double[]{15.45, 18.73});    // Chad
        COORDINATES.put("TGO", new double[]{8.62, 0.82});      // Togo
        COORDINATES.put("TUN", new double[]{33.89, 9.54});     // Tunisia
        COORDINATES.put("TZA", new double[]{-6.37, 34.89});    // Tanzania
        COORDINATES.put("UGA", new double[]{1.37, 32.29});     // Uganda
        COORDINATES.put("ZAF", new double[]{-30.56, 22.94});   // South Africa
        COORDINATES.put("ZMB", new double[]{-13.13, 27.85});   // Zambia
        COORDINATES.put("ZWE", new double[]{-19.02, 29.15});   // Zimbabwe

        // Middle East
        COORDINATES.put("IRQ", new double[]{33.22, 43.68});    // Iraq
        COORDINATES.put("JOR", new double[]{30.59, 36.24});    // Jordan
        COORDINATES.put("LBN", new double[]{33.85, 35.86});    // Lebanon
        COORDINATES.put("PSE", new double[]{31.95, 35.23});    // Palestine
        COORDINATES.put("SYR", new double[]{34.80, 39.00});    // Syria
        COORDINATES.put("YEM", new double[]{15.55, 48.52});    // Yemen
        COORDINATES.put("IRN", new double[]{32.43, 53.69});    // Iran
        COORDINATES.put("SAU", new double[]{23.89, 45.08});    // Saudi Arabia
        COORDINATES.put("TUR", new double[]{38.96, 35.24});    // Turkey

        // Asia
        COORDINATES.put("BGD", new double[]{23.68, 90.35});    // Bangladesh
        COORDINATES.put("BTN", new double[]{27.51, 90.43});    // Bhutan
        COORDINATES.put("CHN", new double[]{35.86, 104.20});   // China
        COORDINATES.put("IDN", new double[]{-0.79, 113.92});   // Indonesia
        COORDINATES.put("IND", new double[]{20.59, 78.96});    // India
        COORDINATES.put("KHM", new double[]{12.57, 104.99});   // Cambodia
        COORDINATES.put("LAO", new double[]{19.86, 102.50});   // Laos
        COORDINATES.put("LKA", new double[]{7.87, 80.77});     // Sri Lanka
        COORDINATES.put("MMR", new double[]{21.91, 95.96});    // Myanmar
        COORDINATES.put("MNG", new double[]{46.86, 103.85});   // Mongolia
        COORDINATES.put("NPL", new double[]{28.39, 84.12});    // Nepal
        COORDINATES.put("PAK", new double[]{30.38, 69.35});    // Pakistan
        COORDINATES.put("PHL", new double[]{12.88, 121.77});   // Philippines
        COORDINATES.put("PRK", new double[]{40.34, 127.51});   // North Korea
        COORDINATES.put("THA", new double[]{15.87, 100.99});   // Thailand
        COORDINATES.put("TJK", new double[]{38.86, 71.28});    // Tajikistan
        COORDINATES.put("TKM", new double[]{38.97, 59.56});    // Turkmenistan
        COORDINATES.put("UZB", new double[]{41.38, 64.59});    // Uzbekistan
        COORDINATES.put("VNM", new double[]{14.06, 108.28});   // Vietnam

        // Latin America & Caribbean
        COORDINATES.put("BOL", new double[]{-16.29, -63.59});  // Bolivia
        COORDINATES.put("COL", new double[]{4.57, -74.30});    // Colombia
        COORDINATES.put("CUB", new double[]{21.52, -77.78});   // Cuba
        COORDINATES.put("DOM", new double[]{18.74, -70.16});   // Dominican Republic
        COORDINATES.put("ECU", new double[]{-1.83, -78.18});   // Ecuador
        COORDINATES.put("GTM", new double[]{15.78, -90.23});   // Guatemala
        COORDINATES.put("HND", new double[]{15.20, -86.24});   // Honduras
        COORDINATES.put("HTI", new double[]{18.97, -72.29});   // Haiti
        COORDINATES.put("NIC", new double[]{12.87, -85.21});   // Nicaragua
        COORDINATES.put("PER", new double[]{-9.19, -75.02});   // Peru
        COORDINATES.put("SLV", new double[]{13.79, -88.90});   // El Salvador
        COORDINATES.put("VEN", new double[]{6.42, -66.59});    // Venezuela

        // Pacific
        COORDINATES.put("FJI", new double[]{-17.71, 178.07});  // Fiji
        COORDINATES.put("PNG", new double[]{-6.31, 143.96});   // Papua New Guinea
        COORDINATES.put("SLB", new double[]{-9.43, 160.02});   // Solomon Islands
        COORDINATES.put("TLS", new double[]{-8.87, 125.73});   // Timor-Leste
        COORDINATES.put("VUT", new double[]{-15.38, 166.96});  // Vanuatu

        // Europe (for refugees context)
        COORDINATES.put("UKR", new double[]{48.38, 31.17});    // Ukraine
        COORDINATES.put("MDA", new double[]{47.41, 28.37});    // Moldova
    }

    /**
     * Get coordinates for a country by ISO3 code
     * @param iso3 ISO3 country code
     * @return array of [latitude, longitude] or null if not found
     */
    public static double[] getCoordinates(String iso3) {
        return COORDINATES.get(iso3);
    }

    /**
     * Get latitude for a country by ISO3 code
     * @param iso3 ISO3 country code
     * @return latitude or null if not found
     */
    public static Double getLatitude(String iso3) {
        double[] coords = COORDINATES.get(iso3);
        return coords != null ? coords[0] : null;
    }

    /**
     * Get longitude for a country by ISO3 code
     * @param iso3 ISO3 country code
     * @return longitude or null if not found
     */
    public static Double getLongitude(String iso3) {
        double[] coords = COORDINATES.get(iso3);
        return coords != null ? coords[1] : null;
    }
}
