package com.crisismonitor.config;

import java.util.*;

/**
 * Single source of truth for monitored countries.
 * All services should use this instead of maintaining separate lists.
 */
public final class MonitoredCountries {

    private MonitoredCountries() {}

    /**
     * Primary crisis countries - actively monitored for situations
     */
    public static final List<String> CRISIS_COUNTRIES = List.of(
        // Africa - East
        "SDN", "SSD", "ETH", "SOM", "KEN", "UGA",
        // Africa - Central
        "COD", "CAF", "TCD", "CMR", "RWA", "BDI",
        // Africa - West (Sahel)
        "NGA", "MLI", "BFA", "NER",
        // Africa - Other
        "LBY", "MOZ",
        // MENA
        "SYR", "IRQ", "YEM", "LBN", "PSE",
        // Asia
        "AFG", "PAK", "BGD", "MMR",
        // LAC (expanded for migration monitoring)
        "HTI", "VEN", "COL", "GTM", "HND", "SLV", "NIC", "MEX", "PER", "ECU", "CUB", "PAN",
        // Europe
        "UKR"
    );

    /**
     * ISO3 to display name mapping (consistent across all services)
     */
    public static final Map<String, String> COUNTRY_NAMES;
    static {
        Map<String, String> names = new LinkedHashMap<>();
        // Africa - East
        names.put("SDN", "Sudan");
        names.put("SSD", "South Sudan");
        names.put("ETH", "Ethiopia");
        names.put("SOM", "Somalia");
        names.put("KEN", "Kenya");
        names.put("UGA", "Uganda");
        // Africa - Central
        names.put("COD", "DR Congo");
        names.put("CAF", "Central African Republic");
        names.put("TCD", "Chad");
        names.put("CMR", "Cameroon");
        names.put("RWA", "Rwanda");
        names.put("BDI", "Burundi");
        // Africa - West
        names.put("NGA", "Nigeria");
        names.put("MLI", "Mali");
        names.put("BFA", "Burkina Faso");
        names.put("NER", "Niger");
        // Africa - Other
        names.put("LBY", "Libya");
        names.put("MOZ", "Mozambique");
        // MENA
        names.put("SYR", "Syria");
        names.put("IRQ", "Iraq");
        names.put("YEM", "Yemen");
        names.put("LBN", "Lebanon");
        names.put("PSE", "Palestine");
        // Asia
        names.put("AFG", "Afghanistan");
        names.put("PAK", "Pakistan");
        names.put("BGD", "Bangladesh");
        names.put("MMR", "Myanmar");
        // LAC
        names.put("HTI", "Haiti");
        names.put("VEN", "Venezuela");
        names.put("COL", "Colombia");
        names.put("GTM", "Guatemala");
        names.put("HND", "Honduras");
        names.put("SLV", "El Salvador");
        names.put("NIC", "Nicaragua");
        names.put("MEX", "Mexico");
        names.put("PER", "Peru");
        names.put("ECU", "Ecuador");
        names.put("CUB", "Cuba");
        names.put("PAN", "Panama");
        // Europe
        names.put("UKR", "Ukraine");
        // North America
        names.put("USA", "United States");
        names.put("CAN", "Canada");
        COUNTRY_NAMES = Collections.unmodifiableMap(names);
    }

    /**
     * ISO3 to GDELT search term mapping
     */
    public static final Map<String, String> GDELT_SEARCH_TERMS;
    static {
        Map<String, String> terms = new LinkedHashMap<>();
        terms.put("SDN", "sudan");
        terms.put("SSD", "south sudan");
        terms.put("ETH", "ethiopia");
        terms.put("SOM", "somalia");
        terms.put("KEN", "kenya");
        terms.put("UGA", "uganda");
        terms.put("COD", "congo");
        terms.put("CAF", "central african republic");
        terms.put("TCD", "chad");
        terms.put("CMR", "cameroon");
        terms.put("RWA", "rwanda");
        terms.put("BDI", "burundi");
        terms.put("NGA", "nigeria");
        terms.put("MLI", "mali");
        terms.put("BFA", "burkina faso");
        terms.put("NER", "niger");
        terms.put("LBY", "libya");
        terms.put("MOZ", "mozambique");
        terms.put("SYR", "syria");
        terms.put("IRQ", "iraq");
        terms.put("YEM", "yemen");
        terms.put("LBN", "lebanon");
        terms.put("PSE", "palestinian");
        terms.put("AFG", "afghanistan");
        terms.put("PAK", "pakistan");
        terms.put("BGD", "bangladesh");
        terms.put("MMR", "myanmar");
        terms.put("HTI", "haiti");
        terms.put("VEN", "venezuela");
        terms.put("COL", "colombia");
        terms.put("GTM", "guatemala");
        terms.put("HND", "honduras");
        terms.put("SLV", "el salvador");
        terms.put("NIC", "nicaragua");
        terms.put("MEX", "mexico");
        terms.put("PER", "peru");
        terms.put("ECU", "ecuador");
        terms.put("CUB", "cuba");
        terms.put("PAN", "panama");
        terms.put("UKR", "ukraine");
        // North America
        terms.put("USA", "united states");
        terms.put("CAN", "canada");
        GDELT_SEARCH_TERMS = Collections.unmodifiableMap(terms);
    }

    /**
     * Country aliases for headline matching
     */
    public static final Map<String, List<String>> COUNTRY_ALIASES;
    static {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("SDN", List.of("sudan", "khartoum", "darfur"));
        aliases.put("SSD", List.of("south sudan", "s. sudan", "s sudan", "juba"));
        aliases.put("ETH", List.of("ethiopia", "ethiopian", "addis ababa", "tigray", "amhara"));
        aliases.put("SOM", List.of("somalia", "somali", "mogadishu", "al-shabaab"));
        aliases.put("KEN", List.of("kenya", "kenyan", "nairobi"));
        aliases.put("UGA", List.of("uganda", "ugandan", "kampala"));
        aliases.put("COD", List.of("congo", "drc", "democratic republic of congo", "kinshasa", "goma", "m23"));
        aliases.put("CAF", List.of("central african republic", "car", "bangui"));
        aliases.put("TCD", List.of("chad", "chadian", "n'djamena"));
        aliases.put("CMR", List.of("cameroon", "cameroonian", "yaounde"));
        aliases.put("RWA", List.of("rwanda", "rwandan", "kigali"));
        aliases.put("BDI", List.of("burundi", "burundian", "bujumbura"));
        aliases.put("NGA", List.of("nigeria", "nigerian", "lagos", "abuja", "boko haram"));
        aliases.put("MLI", List.of("mali", "malian", "bamako"));
        aliases.put("BFA", List.of("burkina faso", "burkinabe", "ouagadougou"));
        aliases.put("NER", List.of("niger", "nigerien", "niamey"));
        aliases.put("LBY", List.of("libya", "libyan", "tripoli", "benghazi"));
        aliases.put("MOZ", List.of("mozambique", "mozambican", "maputo", "cabo delgado"));
        aliases.put("SYR", List.of("syria", "syrian", "damascus", "aleppo", "idlib"));
        aliases.put("IRQ", List.of("iraq", "iraqi", "baghdad", "mosul", "kurdistan"));
        aliases.put("YEM", List.of("yemen", "yemeni", "sanaa", "sana'a", "aden", "houthi"));
        aliases.put("LBN", List.of("lebanon", "lebanese", "beirut", "hezbollah"));
        aliases.put("PSE", List.of("gaza", "palestine", "palestinian", "west bank", "rafah", "hamas"));
        aliases.put("AFG", List.of("afghanistan", "afghan", "kabul", "taliban"));
        aliases.put("PAK", List.of("pakistan", "pakistani", "islamabad", "karachi"));
        aliases.put("BGD", List.of("bangladesh", "bangladeshi", "dhaka", "cox's bazar", "rohingya"));
        aliases.put("MMR", List.of("myanmar", "burma", "burmese", "yangon", "rohingya"));
        aliases.put("HTI", List.of("haiti", "haitian", "port-au-prince"));
        aliases.put("VEN", List.of("venezuela", "venezuelan", "caracas", "maduro"));
        aliases.put("COL", List.of("colombia", "colombian", "bogota", "petro"));
        aliases.put("GTM", List.of("guatemala", "guatemalan", "guatemalans"));
        aliases.put("HND", List.of("honduras", "honduran", "hondurans", "tegucigalpa"));
        aliases.put("SLV", List.of("el salvador", "salvadoran", "salvadorans", "salvadorean", "bukele"));
        aliases.put("NIC", List.of("nicaragua", "nicaraguan", "nicaraguans", "managua"));
        aliases.put("MEX", List.of("mexico", "mexican", "mexicans", "mexico city", "tapachula", "tijuana", "ciudad juarez"));
        aliases.put("PER", List.of("peru", "peruvian", "peruvians", "lima"));
        aliases.put("ECU", List.of("ecuador", "ecuadorian", "ecuadorians", "quito"));
        aliases.put("CUB", List.of("cuba", "cuban", "cubans", "havana"));
        aliases.put("PAN", List.of("panama", "panamanian", "darien", "darien gap"));
        aliases.put("UKR", List.of("ukraine", "ukrainian", "kyiv", "kiev", "kharkiv", "odesa"));
        COUNTRY_ALIASES = Collections.unmodifiableMap(aliases);
    }

    /**
     * ISO3 to region mapping
     */
    public static final Map<String, String> COUNTRY_REGIONS;
    static {
        Map<String, String> regions = new LinkedHashMap<>();
        // Africa
        for (String c : List.of("SDN", "SSD", "ETH", "SOM", "KEN", "UGA", "COD", "CAF", "TCD",
                                "CMR", "RWA", "BDI", "NGA", "MLI", "BFA", "NER", "MOZ")) {
            regions.put(c, "Africa");
        }
        // MENA (includes North Africa: Libya)
        for (String c : List.of("SYR", "IRQ", "YEM", "LBN", "PSE", "LBY")) {
            regions.put(c, "MENA");
        }
        // Asia
        for (String c : List.of("AFG", "PAK", "BGD", "MMR")) {
            regions.put(c, "Asia");
        }
        // LAC (expanded for migration monitoring)
        for (String c : List.of("HTI", "VEN", "COL", "GTM", "HND", "SLV", "NIC", "MEX", "PER", "ECU", "CUB", "PAN")) {
            regions.put(c, "LAC");
        }
        // Europe
        regions.put("UKR", "Europe");
        // North America
        regions.put("USA", "North America");
        regions.put("CAN", "North America");
        COUNTRY_REGIONS = Collections.unmodifiableMap(regions);
    }

    /**
     * Get display name for ISO3 code
     */
    public static String getName(String iso3) {
        return COUNTRY_NAMES.getOrDefault(iso3, iso3);
    }

    /**
     * Get region for ISO3 code
     */
    public static String getRegion(String iso3) {
        return COUNTRY_REGIONS.getOrDefault(iso3, "Global");
    }

    /**
     * Get GDELT search term for ISO3 code
     */
    public static String getGdeltTerm(String iso3) {
        return GDELT_SEARCH_TERMS.getOrDefault(iso3, iso3.toLowerCase());
    }

    /**
     * Check if headline matches country by alias
     */
    public static boolean headlineMatchesCountry(String headline, String iso3) {
        if (headline == null || iso3 == null) return false;
        String lower = headline.toLowerCase();
        List<String> aliases = COUNTRY_ALIASES.get(iso3);
        if (aliases == null) return false;
        for (String alias : aliases) {
            if (lower.contains(alias)) return true;
        }
        return false;
    }
}
