package com.crisismonitor.config;

import java.util.Map;

/**
 * Single source of truth for verified armed conflicts.
 * Used by: QwenScoringService, DailyBriefService, CountryAnalysisGenerator, TopicReportService.
 *
 * Update this map when conflict status changes.
 * Each entry: ISO3 → description of the verified conflict.
 */
public final class VerifiedConflicts {

    private VerifiedConflicts() {}

    public static final Map<String, String> CONFLICTS = Map.ofEntries(
        Map.entry("PSE", "Active war: Israeli military operations in Gaza, siege, ground invasion"),
        Map.entry("IRN", "Active war: US/Israel-Iran military conflict since Feb 2026, Hormuz blockade, ongoing airstrikes"),
        Map.entry("UKR", "Active war: Russia-Ukraine full-scale invasion since Feb 2022"),
        Map.entry("SDN", "Civil war: SAF vs RSF, 150K+ estimated dead, widespread displacement"),
        Map.entry("ISR", "Multi-front conflict: Gaza operations + Iran war"),
        Map.entry("LBN", "Post-2024 war, fragile ceasefire, border tensions"),
        Map.entry("MMR", "Nationwide civil war: resistance forces vs military junta"),
        Map.entry("SYR", "Multi-front civil war, post-Assad transition instability"),
        Map.entry("YEM", "Houthi conflict + US strikes + Red Sea/Hormuz maritime operations"),
        Map.entry("BFA", "Armed groups (JNIM/IS) besieging 40+ towns, population displacement"),
        Map.entry("ETH", "Amhara insurgency (Fano militia), ongoing armed clashes"),
        Map.entry("COD", "M23 + ADF armed group operations, eastern DRC instability"),
        Map.entry("HTI", "Armed gangs control 80-90% of Port-au-Prince"),
        Map.entry("SOM", "Al-Shabaab insurgency, ongoing military operations"),
        Map.entry("SSD", "Inter-communal armed violence, fragile peace"),
        Map.entry("MLI", "JNIM armed group advance, military operations")
    );

    /** Check if a country has a verified armed conflict */
    public static boolean isInConflict(String iso3) {
        return CONFLICTS.containsKey(iso3);
    }

    /** Get conflict description or null */
    public static String getDescription(String iso3) {
        return CONFLICTS.get(iso3);
    }
}
