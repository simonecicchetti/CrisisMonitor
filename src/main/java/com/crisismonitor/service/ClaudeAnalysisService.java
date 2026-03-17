package com.crisismonitor.service;

import com.crisismonitor.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.crisismonitor.config.MonitoredCountries;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * AI Analysis Service using Claude API.
 * Builds compact data packs and generates non-obvious insights.
 * Responses are cached in Redis to minimize API costs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeAnalysisService {

    private final RiskScoreService riskScoreService;
    private final GDELTService gdeltService;
    private final OpenMeteoService openMeteoService;
    private final CurrencyService currencyService;
    private final FewsNetService fewsNetService;
    private final HungerMapService hungerMapService;
    private final WorldBankService worldBankService;
    private final DTMService dtmService;
    private final ClimateService climateService;
    private final RegionalClusterService regionalClusterService;
    private final ReliefWebService reliefWebService;
    private final UNHCRService unhcrService;
    private final StoryService storyService;
    private final CacheWarmupService cacheWarmupService;
    private final IntelligencePrepService intelligencePrepService;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final org.springframework.cache.CacheManager cacheManager;

    @Value("${anthropic.api.key:}")
    private String apiKey;

    @Value("${anthropic.model:claude-3-haiku-20240307}")
    private String model;

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://api.anthropic.com/v1")
            .build();

    // General-purpose WebClient for RSS/external fetches
    private final WebClient rssClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; CrisisMonitor/1.0; +https://crisis-monitor.app)")
            .defaultHeader("Accept", "application/rss+xml, application/xml, text/xml, */*")
            .build();

    // Per-country lock to prevent duplicate builds under concurrent load
    private final Map<String, Object> countryDataPackLocks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Crisis Context — verified intelligence injected into Claude data packs so
     * the model can produce informed briefings even for events beyond its training
     * cutoff. Each entry is a concise situational summary for a country.
     *
     * MAINTENANCE: Update these when major developments occur (new wars, ceasefires,
     * regime changes, famine declarations). Remove entries when crises resolve.
     * Last updated: 2026-03-16
     */
    private static final Map<String, String> CRISIS_CONTEXT = Map.ofEntries(
            // === ACTIVE WARS ===
            Map.entry("IRN", "Active war with US/Israel coalition since Feb 28, 2026 (Operation Epic Fury, Day 16 as of Mar 16). Supreme Leader Khamenei killed in initial strikes; successor Mojtaba Khamenei reportedly injured and transported to Moscow for surgery (unconfirmed). IRGC command structure decapitated — IRGC Ground Forces commander Brig. Gen. Karami possibly eliminated Mar 16. Strait of Hormuz blockaded; US forming multinational coalition to escort shipping (announcement expected this week). First non-Iranian tanker transited Hormuz with AIS on Mar 16. Iran retaliating with drone/missile attacks on UAE (Dubai airport fire, Fujairah oil terminal hit), Bahrain, Kuwait (14 drones intercepted, 3 injuries), Saudi Arabia (64-98 drones in single day record). US Embassy Baghdad under sustained drone attack from Iranian-backed militias (C-RAM active). B-52H and B-1 bombers conducting daily strike missions from England/Kuwait. 6,000+ combat flights completed. Iranian rial collapsed to ~1.75M/USD. Oil exports halted. Iran FM says 'no ceasefire requested.' Trump: 'I don't know who to talk to in Iran, everyone there is dead.'"),
            Map.entry("PSE", "Gaza war ongoing since Oct 7, 2023. Israeli military operations in all parts of Gaza Strip. Full siege: food, water, fuel, medicine severely restricted. IPC Phase 5 (Famine) declared in northern Gaza. Over 40,000 confirmed dead (actual toll likely much higher). 1.9M displaced (90% of population). Infrastructure >70% destroyed. Rafah crossing closed. West Bank: intensified raids, settler violence, 700+ killed since Oct 2023."),
            Map.entry("ISR", "Multi-front war since Oct 7, 2023: Gaza ground operations, Iran war (Feb 2026), Houthi missile/drone attacks. Oct 7 attack: 1,200 killed, 250 hostages. NEW: IDF launched 'limited and targeted ground operations' in southern Lebanon on Mar 16 — Israel approving call-up of up to 450,000 reservists. IDF striking Hezbollah targets in Beirut. Iran front: participated in Feb 28 strikes, IDF expanding strikes on Iranian C2 nodes across western/central Iran. Destroyed Iran's Space Research Institute and state Airbus A340 at Mehrabad Airport. Israeli security apparatus approved war plan for at least 3 more weeks. Economy strained by prolonged mobilization."),
            Map.entry("SDN", "Civil war between SAF and RSF since April 15, 2023. World's largest displacement crisis: 11.8M internally displaced, 2.8M refugees. Famine confirmed in multiple areas of Darfur and Kordofan (IPC Phase 5). Over 150,000 estimated dead. RSF controls most of Darfur and Khartoum. SAF controls Port Sudan and eastern corridor. Systematic ethnic cleansing in El Geneina documented. Aid access severely restricted. No ceasefire prospects."),
            Map.entry("UKR", "Full-scale Russian invasion since Feb 24, 2022. Frontline largely static in eastern Donbas and southern Zaporizhzhia. Russia controls ~18% of Ukrainian territory. Ongoing drone and missile attacks on energy infrastructure. 6.3M internally displaced, 6.4M refugees in Europe. Kursk Oblast incursion (Aug 2024) partially recaptured by Russia. Negotiations stalled. US military aid uncertain under current administration."),

            // === CIVIL WARS / HIGH-INTENSITY CONFLICT ===
            Map.entry("MMR", "Post-coup civil war since Feb 2021. Junta controls only ~21% of territory. Resistance forces (PDF + ethnic armed organizations) advancing on multiple fronts. Mandalay potentially threatened. 3.4M displaced. Economy collapsed: kyat lost 80% value. Rohingya crisis ongoing in Rakhine. Internet blackouts. Conscription law driving mass emigration."),
            Map.entry("SYR", "Post-Assad transition underway since Dec 2024 when HTS-led forces took Damascus. Assad fled to Russia. New transitional government under Ahmed al-Sharaa (Abu Mohammed al-Julani). Sanctions partially lifted. Massive reconstruction needs. Northeast: SDF/Kurdish areas autonomous. ISIS remnants active in central desert. 7.2M still internally displaced. Fragile stability, sectarian tensions persist."),
            Map.entry("YEM", "Houthi-government conflict frozen but Houthi Red Sea campaign ongoing since Nov 2023 (solidarity with Gaza). US/UK strikes on Houthi positions. Strait of Bab el-Mandeb shipping disrupted. 21.6M need humanitarian aid. Economy divided between Aden government and Sanaa Houthis. Iran war (Feb 2026) intensified Houthi attacks as proxy escalation."),
            Map.entry("ETH", "Tigray war officially ended (Pretoria Agreement Nov 2022) but Amhara insurgency (Fano militia) intensifying since Aug 2023. State of emergency in Amhara region. Drone strikes on civilian areas. Oromia: OLA insurgency ongoing. 20M+ food insecure. Inflation ~30%. 1.55M displaced from Amhara conflict alone. Government increasingly authoritarian."),
            Map.entry("SSD", "Fragile 2018 peace deal barely holding. Inter-communal violence in Jonglei and Upper Nile. Sudan war spillover: 800K+ refugees from Sudan. 76% of population food insecure. Oil production declining. Transitional period extended repeatedly. Economic collapse: SSP worthless. UN peacekeeping (UNMISS) present but limited."),
            Map.entry("COD", "Eastern DRC: M23 rebellion (Rwanda-backed) controls large swaths of North Kivu including approach to Goma. ADF/ISIS affiliate active in Ituri. Over 100 armed groups. 7.2M displaced (largest in Africa after Sudan). Cobalt/mineral exploitation fueling conflict. MONUSCO withdrawal underway. Regional tensions with Rwanda at breaking point."),

            // === SEVERE HUMANITARIAN CRISES ===
            Map.entry("HTI", "Gang coalitions control 80-90% of Port-au-Prince. Expanding to provinces (Artibonite). 1.4M displaced. Police force <3,000 functional officers. Kenyan-led MSS force deployed but insufficient. 5.9M food insecure, 600K in famine-like conditions. Vigilante justice (bwa kale) widespread. No functioning government. Infrastructure collapsed."),
            Map.entry("BFA", "JNIM and IS-Sahel control >50% of territory. 40+ towns under jihadi siege — populations starving. 2.1M IDPs. Military junta (Traoré) expelled French forces, relies on Russian Wagner/Africa Corps. Gold mining revenue funds junta but doesn't reach besieged populations. 38+ killed in attacks Feb 2026. Media blackout on conflict zones."),
            Map.entry("MLI", "JNIM controls large areas, advancing toward Bamako. Military junta expelled MINUSMA, French, turned to Russia/Wagner. Supply routes to northern cities cut. 1.3M food insecure. Tuareg rebellion in north (separate from jihadi insurgency). Wagner suffered major defeat at Tinzaouaten (Jul 2024). Bamako increasingly isolated."),
            Map.entry("SOM", "Al-Shabaab controls rural areas of south-central Somalia. Government offensive stalled. Drought recovery but food insecurity persists. 3.8M displaced. Tensions with Ethiopia over MOU with Somaliland. ATMIS peacekeeping drawdown. Clan dynamics complicate governance. Mogadishu relatively stable but attacks continue."),
            Map.entry("AFG", "CRITICAL Mar 16: Major Pakistani military strikes hitting Kabul — cross-border escalation unprecedented since Taliban takeover. Taliban rule since Aug 2021. IS-K attacks continue. Women banned from education, work, public spaces. 97% near poverty. Banking frozen. 23.7M need humanitarian aid. Opium ban devastating livelihoods. 1.7M refugees returned from Pakistan. International engagement minimal."),

            // === ECONOMIC/POLITICAL CRISES ===
            Map.entry("LBN", "CRITICAL ESCALATION Mar 16: IDF launched new 'limited and targeted ground operations' in southern Lebanon against Hezbollah. IDF striking Hezbollah targets throughout Beirut. Israel calling up 450,000 reservists. Five European nations (Germany, France, Italy, Canada, UK) oppose major Israeli ground offensive. Post-2024 war (Sep-Nov 2024): 4,000+ killed, southern infrastructure devastated. Previous ceasefire collapsed. 80% below poverty line. Banking sector collapsed since 2019. Hezbollah firing Grad rockets at Israeli military facilities."),
            Map.entry("CUB", "ESCALATION Mar 16: Trump stated 'I believe I'll have the honor of taking Cuba' and is pushing to remove Díaz-Canel. US-Cuba negotiations ongoing. US oil blockade tightened 2025-2026. GDP contracted 7.2%. Rolling blackouts 12-18 hours daily. Severe food rationing. Mass emigration (300K+ in 2024 via Mexico). Dual currency collapse. Healthcare system near non-functional. Worst economic crisis since 1990s Special Period."),
            Map.entry("VEN", "Maduro retained power after disputed Jul 2024 election. Opposition leader González in exile. 7.7M refugees/migrants (second largest crisis globally after Ukraine). Sanctions partially reimposed. Oil production recovering but below potential. Hyperinflation controlled but poverty ~82%. Essequibo territorial dispute with Guyana unresolved."),
            Map.entry("NGA", "35M at risk of hunger (record high). WFP defunded, forced to cut operations. Northeast: ISWAP/Boko Haram intensifying. Northwest: mass kidnappings, banditry. 40.9% food inflation. Naira collapse after float. Fuel subsidy removal drove prices up 300%. Security forces overstretched across multiple fronts. Climate: severe flooding 2024 displaced 1.4M."),
            Map.entry("PAK", "MAJOR ESCALATION Mar 16: Pakistan conducting strikes in Afghan capital Kabul — significant cross-border military action. TTP insurgency intensifying in KP and Balochistan. 2024 deadliest year since 2014. Economic crisis: IMF bailout conditions. Political instability: Imran Khan imprisoned. Iran war spillover (border incidents). Climate: 2022 floods recovery still incomplete. 40% children malnourished."),

            // === FRAGILE/WATCHLIST ===
            Map.entry("LBY", "Two rival governments: GNU (Tripoli) and LNA-backed (Benghazi). Militia competition for oil revenue. No elections since 2014. Derna flood disaster (Sep 2023): 11,000+ dead, government negligence. Migrant detention/abuse systematic. Oil production volatile (~1.2M bpd). Wagner/Russian presence in east."),
            Map.entry("CAF", "Armed groups control ~80% of territory. Wagner/Russia Corps supports Touadéra government. MINUSCA peacekeeping present but limited. 3.4M need humanitarian aid. Diamond/gold exploitation by armed groups and Wagner. Ethnic tensions between Séléka-aligned and anti-balaka. Fragile peace agreements not holding."),
            Map.entry("TCD", "Sudan war spillover: 1M+ Sudanese refugees, border closed Feb 2026. Chadian army involved in Darfur operations. RSF incursions across border. Host communities food insecure. Déby dynasty continues (Mahamat Déby won contested 2024 election). Lake Chad shrinking, Boko Haram spillover from Nigeria."),
            Map.entry("CMR", "Anglophone crisis (2017-present): separatist conflict in Northwest/Southwest regions. 600K displaced. Boko Haram spillover in Far North. Biya (91 years old, president since 1982) — succession crisis looming. Democratic space shrinking. French-English divide deepening."),
            Map.entry("MOZ", "Cabo Delgado insurgency (IS-linked) since 2017. Rwandan/SADC forces partially restored control. Major LNG projects (TotalEnergies) suspended. 1M displaced in north. Contested 2024 election sparked nationwide protests. Economic potential undermined by conflict and governance failures."),
            Map.entry("NER", "Military junta since Jul 2023 coup. Expelled French/US forces. ECOWAS sanctions lifted. Jihadi spillover from BFA/MLI intensifying. Uranium exports disrupted. Alliance with Mali and Burkina (AES). Democratic reversals. Food insecurity increasing in western regions near BFA border."),
            Map.entry("COL", "FARC dissidents (EMC, Segunda Marquetalia) and ELN active. Total Peace policy negotiations stalled. Coca production at record levels. Venezuela border tensions. Environmental defenders targeted. Indigenous communities displaced by armed groups in Pacific coast (Chocó). Urban violence in Cali, Buenaventura."),
            Map.entry("ZWE", "Post-Mugabe era: Mnangagwa consolidating power. ZiG currency introduced 2024 (replacing collapsed ZWL). Inflation controlled but poverty ~50%. Election 2023 disputed. Civil liberties restricted. Climate: El Niño drought devastated 2024 harvest. 7.7M food insecure. Cholera outbreaks. Diaspora remittances key to survival."),
            Map.entry("IRQ", "ACTIVE COMBAT ZONE as of Mar 16: US Embassy Baghdad under sustained drone attack from Iranian-backed militias (Popular Mobilization Front, Saraya Awliya al-Dam). C-RAM air defense actively engaging Shahed-136 drones over Baghdad. US airstrikes leveling militia positions in Mosul. A-10 Warthogs conducting close air support in northern Iraq. Iraqi airspace contested. Al-Rashid Hotel (EU mission, Austrian/Swedish embassies) targeted by drone. HIMARS launching ATACMS from Kuwait into Iran. Iraq caught between US and Iranian operations on its soil.")
    );

    // Redis key for Claude situation detection cache (4 hour TTL)
    private static final String CLAUDE_SITUATIONS_CACHE_KEY = "claudeSituations::latest";

    // In-memory fallback for Claude situation detection (when Redis is unavailable)
    private volatile SituationDetectionResult cachedSituationInMemory;

    /**
     * Global analysis - overview of all monitored countries
     */
    @Cacheable(value = "aiAnalysisGlobal", key = "'global:' + #dataVersion")
    public AIAnalysis analyzeGlobal(String dataVersion) {
        log.info("Generating global AI analysis (cache miss)");

        // Build news signal for top risk country
        NewsSignal newsSignal = buildTopNewsSignal();

        String dataPack = buildGlobalDataPack();
        String prompt = buildGlobalPrompt(dataPack, newsSignal);

        AIAnalysis analysis = callClaude(prompt, "global", null, null, newsSignal);
        analysis.setDataVersion(dataVersion);
        analysis.setFromCache(false);
        analysis.setNewsSignal(newsSignal);

        return analysis;
    }

    /**
     * Country-specific narrative analysis with citations.
     */
    @Cacheable(value = "aiAnalysisCountry", key = "#iso3 + ':' + #dataVersion")
    public AIAnalysis analyzeCountry(String iso3, String dataVersion) {
        log.info("Generating country AI analysis for {} (cache miss)", iso3);

        CountryDataPackResult packResult = buildCountryDataPackWithSources(iso3);
        if (packResult == null) {
            return AIAnalysis.builder()
                    .scope("country")
                    .countryIso3(iso3)
                    .keyFindings(List.of("Country not found in monitored list"))
                    .drivers(List.of())
                    .watchList(List.of())
                    .generatedAt(LocalDateTime.now())
                    .model(model)
                    .fromCache(false)
                    .build();
        }

        String countryName = getCountryName(iso3);
        String prompt = buildCountryNarrativePrompt(packResult.dataPack, countryName, packResult.riskLevel, packResult.riskScore);

        // Call Claude for narrative response
        AIAnalysis analysis = callClaudeNarrative(prompt, iso3, countryName, packResult.sources);
        analysis.setDataVersion(dataVersion);
        analysis.setFromCache(false);
        analysis.setRiskLevel(packResult.riskLevel);
        analysis.setRiskScore(packResult.riskScore);

        return analysis;
    }

    /** Intermediate result holding data pack text + numbered sources */
    private static class CountryDataPackResult {
        String dataPack;
        List<QASource> sources;
        String riskLevel;
        int riskScore;
    }

    /**
     * Regional briefing analysis - intelligence brief for a specific region
     * Regions: Africa, LAC, MENA, Asia, Europe
     */
    @Cacheable(value = "aiAnalysisRegionV2", key = "#region + ':' + #dataVersion")
    public AIAnalysis analyzeRegion(String region, String dataVersion) {
        log.info("Generating regional AI analysis for {} (cache miss)", region);

        String dataPack = buildRegionalDataPack(region);
        if (dataPack == null || dataPack.isEmpty()) {
            return AIAnalysis.builder()
                    .scope("region")
                    .region(region)
                    .keyFindings(List.of("No data available for region: " + region))
                    .drivers(List.of())
                    .watchList(List.of())
                    .generatedAt(LocalDateTime.now())
                    .model(model)
                    .fromCache(false)
                    .build();
        }

        String prompt = buildRegionalPrompt(dataPack, region);
        AIAnalysis analysis = callClaudeRegional(prompt, region);
        analysis.setDataVersion(dataVersion);
        analysis.setFromCache(false);
        analysis.setRegion(region);

        return analysis;
    }

    /**
     * Generate a version hash based on current date and hour.
     * Analysis is refreshed hourly to get new insights.
     */
    public String generateDataVersion() {
        // Hourly version - refreshes analysis once per hour
        LocalDateTime now = LocalDateTime.now();
        return now.toLocalDate().toString() + "-" + now.getHour() + "-v7"; // v7 = added ReliefWeb humanitarian intel
    }

    /**
     * Build compact data pack for global analysis - ONLY USER-VISIBLE METRICS
     *
     * Visible in dashboard:
     * - Risk Score (0-100) + Risk Level (CRITICAL, ALERT, WARNING, WATCH, STABLE)
     * - IPC Phase (1-5) + People in IPC 3+
     * - Inflation % (from World Bank)
     * - IDPs (Internally Displaced Persons)
     * - NDVI Alert Level (SEVERE, MODERATE, NORMAL)
     *
     * NOT visible (do not include):
     * - Internal scores (Climate Score, Conflict Score, Economic Score, Food Security Score)
     * - z-scores, article counts, precipitation anomaly %, currency change %
     */
    @SuppressWarnings("unchecked")
    private String buildGlobalDataPack() {
        StringBuilder pack = new StringBuilder();

        // ===== RISK SCORES =====
        List<RiskScore> scores = cacheWarmupService != null
                ? (List<RiskScore>) cacheWarmupService.getFallback("allRiskScores") : null;
        if (scores == null || scores.isEmpty()) {
            scores = riskScoreService.getAllRiskScores();
        }

        pack.append("## RISK SCORES\n");
        pack.append("Countries by Risk Level (visible in dashboard):\n\n");

        // Critical + Alert (score >= 71)
        List<RiskScore> highRisk = scores.stream()
                .filter(s -> s.getScore() >= 71)
                .sorted((a, b) -> b.getScore() - a.getScore())
                .toList();

        for (RiskScore s : highRisk) {
            pack.append(String.format("- %s: Risk Score %d (%s)\n",
                    s.getCountryName(), s.getScore(), s.getRiskLevel()));
        }

        // Warning (51-70)
        List<RiskScore> warning = scores.stream()
                .filter(s -> s.getScore() >= 51 && s.getScore() < 71)
                .sorted((a, b) -> b.getScore() - a.getScore())
                .toList();

        pack.append("\nWarning level:\n");
        for (RiskScore s : warning) {
            pack.append(String.format("- %s: Risk Score %d (WARNING)\n",
                    s.getCountryName(), s.getScore()));
        }

        // ===== FOOD SECURITY (IPC) =====
        pack.append("\n## FOOD SECURITY (IPC Phases)\n");

        List<IPCAlert> ipcAlerts = fewsNetService.getCriticalAlerts();
        Map<String, Country> ipcData = hungerMapService.getIpcData();

        // Phase 5 (Famine)
        List<IPCAlert> phase5 = ipcAlerts.stream()
                .filter(a -> a.getIpcPhase() != null && a.getIpcPhase() >= 5.0)
                .toList();
        if (!phase5.isEmpty()) {
            pack.append("\nPhase 5 (Famine):\n");
            for (IPCAlert a : phase5) {
                Country ipc = ipcData.get(a.getIso2() != null ?
                        getIso3FromIso2(a.getIso2()) : null);
                String population = ipc != null && ipc.getPeoplePhase3to5() != null ?
                        formatPopulation(ipc.getPeoplePhase3to5()) + " people IPC 3+" : "";
                pack.append(String.format("- %s: IPC Phase 5 (Famine)%s\n",
                        a.getCountryName(), population.isEmpty() ? "" : "; " + population));
            }
        }

        // Phase 4 (Emergency)
        List<IPCAlert> phase4 = ipcAlerts.stream()
                .filter(a -> a.getIpcPhase() != null && a.getIpcPhase() >= 4.0 && a.getIpcPhase() < 5.0)
                .toList();
        if (!phase4.isEmpty()) {
            pack.append("\nPhase 4 (Emergency):\n");
            for (IPCAlert a : phase4) {
                Country ipc = ipcData.get(a.getIso2() != null ?
                        getIso3FromIso2(a.getIso2()) : null);
                String population = ipc != null && ipc.getPeoplePhase3to5() != null ?
                        formatPopulation(ipc.getPeoplePhase3to5()) + " people IPC 3+" : "";
                pack.append(String.format("- %s: IPC Phase 4 (Emergency)%s\n",
                        a.getCountryName(), population.isEmpty() ? "" : "; " + population));
            }
        }

        // ===== INFLATION (IMF WEO) =====
        pack.append("\n## INFLATION\n");
        try {
            List<EconomicIndicator> highInflation = worldBankService.getHighInflationCountries();
            if (highInflation != null && !highInflation.isEmpty()) {
                for (EconomicIndicator e : highInflation.stream().limit(10).toList()) {
                    pack.append(String.format("- %s: Inflation %.1f%% (%d)\n",
                            e.getCountryName(), e.getValue(), e.getYear()));
                }
            } else {
                pack.append("(No high inflation data available)\n");
            }
        } catch (Exception e) {
            pack.append("(Inflation data not available)\n");
        }

        // ===== IDPs (Internally Displaced Persons) =====
        pack.append("\n## INTERNALLY DISPLACED PERSONS (IDPs)\n");
        try {
            List<MobilityStock> idpData = dtmService.getCountryLevelIdps();
            if (idpData != null && !idpData.isEmpty()) {
                for (MobilityStock m : idpData.stream()
                        .filter(m -> m.getIdps() != null && m.getIdps() > 100000)
                        .limit(10)
                        .toList()) {
                    pack.append(String.format("- %s: %s IDPs\n",
                            m.getCountryName(), formatPopulation(m.getIdps())));
                }
            } else {
                pack.append("(IDP data not available)\n");
            }
        } catch (Exception e) {
            pack.append("(IDP data not available)\n");
        }

        // ===== CLIMATE STRESS (NDVI) =====
        pack.append("\n## CLIMATE STRESS (NDVI)\n");
        try {
            List<ClimateData> climateStress = climateService.getCountriesWithClimateStress();
            if (climateStress != null && !climateStress.isEmpty()) {
                List<ClimateData> severe = climateStress.stream()
                        .filter(c -> "SEVERE".equals(c.getAlertLevel()))
                        .toList();
                List<ClimateData> moderate = climateStress.stream()
                        .filter(c -> "MODERATE".equals(c.getAlertLevel()))
                        .toList();

                if (!severe.isEmpty()) {
                    pack.append("SEVERE drought stress: ");
                    pack.append(severe.stream().map(ClimateData::getName).collect(Collectors.joining(", ")));
                    pack.append("\n");
                }
                if (!moderate.isEmpty()) {
                    pack.append("MODERATE drought stress: ");
                    pack.append(moderate.stream().map(ClimateData::getName).limit(8).collect(Collectors.joining(", ")));
                    pack.append("\n");
                }
            } else {
                pack.append("(Climate data not available)\n");
            }
        } catch (Exception e) {
            pack.append("(Climate data not available)\n");
        }

        // ===== CROSS-SIGNAL COUNTRIES =====
        pack.append("\n## CROSS-SIGNAL ANALYSIS (countries appearing in multiple indicators)\n");
        Map<String, Integer> crossSignal = new HashMap<>();

        // Count appearances
        for (RiskScore s : highRisk) {
            crossSignal.merge(s.getCountryName(), 1, Integer::sum);
        }
        for (IPCAlert a : ipcAlerts) {
            if (a.getIpcPhase() != null && a.getIpcPhase() >= 4.0) {
                crossSignal.merge(a.getCountryName(), 1, Integer::sum);
            }
        }

        // List countries in 2+ categories
        crossSignal.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> pack.append(String.format("- %s: appears in %d indicator categories\n",
                        e.getKey(), e.getValue())));

        // ===== REGIONAL CLUSTER ALERTS =====
        pack.append("\n## REGIONAL CLUSTER ALERTS\n");
        try {
            List<RegionalClusterService.ClusterAlert> clusters = regionalClusterService.analyzeRegionalClusters(scores);
            if (clusters != null && !clusters.isEmpty()) {
                for (RegionalClusterService.ClusterAlert cluster : clusters) {
                    pack.append(String.format("- %s: %s — %s\n",
                            cluster.getClusterName(),
                            cluster.getStatus(),
                            cluster.getDescription()));
                    if (cluster.getAffectedCountries() != null && !cluster.getAffectedCountries().isEmpty()) {
                        pack.append("  Affected: ").append(String.join(", ", cluster.getAffectedCountries())).append("\n");
                    }
                }
            } else {
                pack.append("No significant regional cluster alerts.\n");
            }
        } catch (Exception e) {
            pack.append("(Regional cluster data not available)\n");
        }

        return pack.toString();
    }

    private String formatPopulation(Long pop) {
        if (pop == null) return "N/A";
        if (pop >= 1_000_000) return String.format("%.1fM", pop / 1_000_000.0);
        if (pop >= 1_000) return String.format("%.0fK", pop / 1_000.0);
        return pop.toString();
    }

    /**
     * Build NewsSignal for the highest-risk country with media spike.
     * Provides news context for the AI Brief.
     */
    private NewsSignal buildTopNewsSignal() {
        try {
            // Get top risk country
            List<RiskScore> scores = riskScoreService.getAllRiskScores();
            if (scores == null || scores.isEmpty()) return null;

            RiskScore topRisk = scores.stream()
                    .filter(s -> s.getScore() >= 51) // At least WARNING level
                    .max((a, b) -> Integer.compare(a.getScore(), b.getScore()))
                    .orElse(scores.get(0));

            String iso3 = topRisk.getIso3();
            String countryName = topRisk.getCountryName();
            String riskLevel = topRisk.getRiskLevel();
            int riskScore = topRisk.getScore();

            // Get media spike data
            MediaSpike spike = gdeltService.getConflictSpikeIndex(iso3);
            if (spike == null) return null;

            Double zScore = spike.getZScore();
            Integer articles = spike.getArticlesLast7Days();

            // Calculate news level
            String newsLevel = NewsSignal.calculateLevel(zScore, articles);
            String levelIcon = NewsSignal.getLevelIcon(newsLevel);

            // Calculate convergence
            String convergence = NewsSignal.calculateConvergence(riskLevel, newsLevel);
            String convergenceIcon = NewsSignal.getConvergenceIcon(convergence);

            // Format spike stat
            String spikeStat = String.format("%d articles / 7d (Z=%.1f)",
                    articles != null ? articles : 0,
                    zScore != null ? zScore : 0.0);

            // Get headlines with URLs (GDELT - media)
            List<Headline> headlines = gdeltService.getTopHeadlinesWithUrls(iso3, 3);

            // Get humanitarian reports (ReliefWeb - official UN/NGO reports)
            List<Headline> humanitarianReports = reliefWebService.getLatestReportsAsHeadlines(iso3, 3);

            return NewsSignal.builder()
                    .iso3(iso3)
                    .countryName(countryName)
                    .level(newsLevel)
                    .levelIcon(levelIcon)
                    .convergenceTag(convergence)
                    .convergenceIcon(convergenceIcon)
                    .convergent(convergence != null && convergence.contains("Convergent"))
                    .articlesLast7Days(articles)
                    .zScore(zScore)
                    .spikeStat(spikeStat)
                    .headlines(headlines)
                    .humanitarianReports(humanitarianReports)
                    .riskLevel(riskLevel)
                    .riskScore(riskScore)
                    .build();

        } catch (Exception e) {
            log.error("Error building news signal: {}", e.getMessage());
            return null;
        }
    }

    private String getIso3FromIso2(String iso2) {
        return ISO2_TO_ISO3_MAP.getOrDefault(iso2, iso2);
    }

    private String getIso2FromIso3(String iso3) {
        return ISO3_TO_ISO2_MAP.get(iso3);
    }

    // Static ISO mappings (covers all monitored countries)
    private static final Map<String, String> ISO2_TO_ISO3_MAP = Map.ofEntries(
            Map.entry("SD", "SDN"), Map.entry("SS", "SSD"), Map.entry("SO", "SOM"),
            Map.entry("ET", "ETH"), Map.entry("YE", "YEM"), Map.entry("AF", "AFG"),
            Map.entry("CD", "COD"), Map.entry("HT", "HTI"), Map.entry("CF", "CAF"),
            Map.entry("NG", "NGA"), Map.entry("ML", "MLI"), Map.entry("NE", "NER"),
            Map.entry("BF", "BFA"), Map.entry("TD", "TCD"), Map.entry("MZ", "MOZ"),
            Map.entry("MM", "MMR"), Map.entry("SY", "SYR"), Map.entry("UA", "UKR"),
            Map.entry("LB", "LBN"), Map.entry("VE", "VEN"), Map.entry("ZW", "ZWE"),
            Map.entry("KE", "KEN"), Map.entry("UG", "UGA"), Map.entry("IQ", "IRQ"),
            Map.entry("IR", "IRN"), Map.entry("PK", "PAK"), Map.entry("BD", "BGD"),
            Map.entry("IN", "IND"), Map.entry("PH", "PHL"), Map.entry("ID", "IDN"),
            Map.entry("VN", "VNM"), Map.entry("PE", "PER"), Map.entry("CO", "COL"),
            Map.entry("EC", "ECU"), Map.entry("GT", "GTM"), Map.entry("HN", "HND"),
            Map.entry("PS", "PSE"), Map.entry("LY", "LBY"), Map.entry("SV", "SLV"),
            Map.entry("NI", "NIC"), Map.entry("MX", "MEX"), Map.entry("CU", "CUB"),
            Map.entry("PA", "PAN"), Map.entry("US", "USA"), Map.entry("CA", "CAN"),
            Map.entry("RW", "RWA"), Map.entry("BI", "BDI"), Map.entry("CM", "CMR"),
            Map.entry("JO", "JOR"), Map.entry("NP", "NPL"), Map.entry("BO", "BOL")
    );

    private static final Map<String, String> ISO3_TO_ISO2_MAP;
    static {
        Map<String, String> reverse = new HashMap<>();
        ISO2_TO_ISO3_MAP.forEach((iso2, iso3) -> reverse.put(iso3, iso2));
        ISO3_TO_ISO2_MAP = Collections.unmodifiableMap(reverse);
    }

    /**
     * Get country data pack, using Redis cache to avoid rebuilding for every user.
     * With 1000 concurrent users, only the first request builds the pack;
     * all others get it from cache.
     */
    public String getCountryDataPack(String iso3) {
        try {
            org.springframework.cache.Cache cache = cacheManager.getCache("countryDataPack");
            // Fast path: cache hit (no lock needed)
            if (cache != null) {
                String cached = cache.get(iso3, String.class);
                if (cached != null) return cached;
            }
            // Slow path: lock per-country so only one thread builds
            synchronized (countryDataPackLocks.computeIfAbsent(iso3, k -> new Object())) {
                // Double-check after acquiring lock
                if (cache != null) {
                    String cached = cache.get(iso3, String.class);
                    if (cached != null) return cached;
                }
                String pack = buildCountryDataPack(iso3);
                if (pack != null && cache != null) {
                    cache.put(iso3, pack);
                }
                return pack;
            }
        } catch (Exception e) {
            log.debug("Country data pack cache error for {}, building fresh: {}", iso3, e.getMessage());
            return buildCountryDataPack(iso3);
        }
    }

    /**
     * Build compact data pack for country-specific analysis.
     * GDELT headlines inside are individually cached (4h) by GDELTService.
     */
    private String buildCountryDataPack(String iso3) {
        // Read risk score from cache ONLY — never trigger GDELT API calls during briefing
        String iso2 = getIso2FromIso3(iso3);
        RiskScore country = null;
        if (iso2 != null) {
            try {
                org.springframework.cache.Cache riskCache = cacheManager.getCache("riskScore");
                if (riskCache != null) {
                    country = riskCache.get(iso2, RiskScore.class);
                }
            } catch (Exception e) {
                log.debug("Risk score cache read failed for {}: {}", iso2, e.getMessage());
            }
            // Fallback: calculate only if cache miss (this may block on GDELT, but only on first request)
            if (country == null) {
                country = riskScoreService.calculateRiskScore(iso2);
            }
        }

        if (country == null) return null;

        StringBuilder pack = new StringBuilder();

        // Full risk score breakdown
        pack.append("## ").append(country.getCountryName().toUpperCase()).append(" RISK PROFILE\n");
        pack.append(String.format("Overall Score: %d/100 (%s)\n", country.getScore(), country.getRiskLevel()));
        pack.append(String.format("Confidence: %.0f%%\n", country.getConfidence() * 100));
        pack.append(String.format("Horizon: %s - %s\n\n", country.getHorizon(), country.getHorizonReason()));

        pack.append("### INDICATOR BREAKDOWN\n");
        pack.append(String.format("- Climate: %d/100 (Precip anomaly: %.1f%%)\n",
                country.getClimateScore(),
                country.getPrecipitationAnomaly() != null ? country.getPrecipitationAnomaly() : 0));
        pack.append(String.format("- Conflict: %d/100 (GDELT z-score: %.1f)\n",
                country.getConflictScore(),
                country.getGdeltZScore() != null ? country.getGdeltZScore() : 0));
        pack.append(String.format("- Economic: %d/100 (Currency 30d: %.1f%%)\n",
                country.getEconomicScore(),
                country.getCurrencyChange30d() != null ? country.getCurrencyChange30d() : 0));
        pack.append(String.format("- Food Security: %d/100 (IPC Phase: %.0f)\n",
                country.getFoodSecurityScore(),
                country.getIpcPhase() != null ? country.getIpcPhase() : 0));

        pack.append("\n### ELEVATED INDICATORS (2-of-3 rule)\n");
        if (country.isClimateElevated()) pack.append("- Climate: ELEVATED\n");
        if (country.isConflictElevated()) pack.append("- Conflict: ELEVATED\n");
        if (country.isEconomicElevated()) pack.append("- Economic: ELEVATED\n");
        pack.append(String.format("Confirmation: %d/3 elevated\n", country.getElevatedCount()));

        pack.append("\n### PRIMARY DRIVERS\n");
        if (country.getDrivers() != null) {
            for (String driver : country.getDrivers()) {
                pack.append("- ").append(driver).append("\n");
            }
        }

        // === RECENT NEWS (multiple sources, fetched in parallel) ===
        pack.append("\n### RECENT NEWS\n");

        String countryName = MonitoredCountries.getName(iso3);

        // Launch Google News + ReliefWeb in parallel
        CompletableFuture<List<Headline>> googleNewsFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return fetchGoogleNewsHeadlines(countryName, 10);
            } catch (Exception e) {
                log.debug("Google News fetch failed for {}: {}", iso3, e.getMessage());
                return Collections.<Headline>emptyList();
            }
        });

        CompletableFuture<List<Headline>> reliefWebFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return reliefWebService.getLatestReportsAsHeadlines(iso3, 5, 7);
            } catch (Exception e) {
                log.debug("ReliefWeb reports failed for {}: {}", iso3, e.getMessage());
                return Collections.<Headline>emptyList();
            }
        });

        try {
            CompletableFuture.allOf(googleNewsFuture, reliefWebFuture).join();
        } catch (Exception e) {
            log.debug("Parallel news fetch error: {}", e.getMessage());
        }

        // 1. Google News RSS
        try {
            List<Headline> googleNews = googleNewsFuture.join();
            if (!googleNews.isEmpty()) {
                pack.append("Latest news headlines:\n");
                googleNews.forEach(h -> pack.append("- ").append(h.getTitle())
                        .append(h.getSource() != null ? " [" + h.getSource() + "]" : "").append("\n"));
            }
        } catch (Exception e) {
            log.debug("Google News result failed for {}: {}", iso3, e.getMessage());
        }

        // 2. GDELT conflict headlines (non-blocking: only if already cached)
        try {
            org.springframework.cache.Cache spikeCache = cacheManager.getCache("gdeltSpikeIndex");
            if (spikeCache != null) {
                MediaSpike spike = spikeCache.get(iso3, MediaSpike.class);
                if (spike != null && spike.getTopHeadlines() != null && !spike.getTopHeadlines().isEmpty()) {
                    pack.append("Conflict/security media:\n");
                    spike.getTopHeadlines().stream().limit(3).forEach(h -> pack.append("- ").append(h).append("\n"));
                }
            }
        } catch (Exception e) {
            log.debug("GDELT cache not ready for {}: {}", iso3, e.getMessage());
        }

        // 3. ReliefWeb humanitarian reports (last 7 days)
        try {
            List<Headline> reports = reliefWebFuture.join();
            if (reports != null && !reports.isEmpty()) {
                pack.append("Humanitarian reports:\n");
                reports.forEach(r -> pack.append("- ").append(r.getTitle())
                        .append(r.getSource() != null ? " [" + r.getSource() + "]" : "").append("\n"));
            }
        } catch (Exception e) {
            log.debug("ReliefWeb result failed for {}: {}", iso3, e.getMessage());
        }

        // Compare to regional peers (non-blocking: read from cache only)
        try {
            org.springframework.cache.Cache riskCache = cacheManager.getCache("allRiskScores");
            @SuppressWarnings("unchecked")
            List<RiskScore> allScores = riskCache != null ? riskCache.get("SimpleKey []", List.class) : null;
            if (allScores != null && !allScores.isEmpty()) {
                pack.append("\n### REGIONAL CONTEXT\n");
                String region = getRegion(iso3);
                String peersStr = allScores.stream()
                        .filter(s -> region.equals(getRegion(s.getIso3())))
                        .filter(s -> !iso3.equalsIgnoreCase(s.getIso3()))
                        .sorted((a, b) -> b.getScore() - a.getScore())
                        .limit(3)
                        .map(s -> String.format("%s(%d)", s.getCountryName(), s.getScore()))
                        .collect(Collectors.joining(", "));
                pack.append("Regional peers: ").append(peersStr);
            }
        } catch (Exception e) {
            log.debug("Regional peers cache not ready: {}", e.getMessage());
        }

        return pack.toString();
    }

    /**
     * Build country data pack WITH numbered sources for narrative analysis.
     * Returns both the data pack text and a list of citable sources with URLs.
     */
    private CountryDataPackResult buildCountryDataPackWithSources(String iso3) {
        // Get risk score from memory fallback (avoids Redis sync=true blocking)
        String iso2 = getIso2FromIso3(iso3);
        RiskScore country = null;
        if (iso2 != null) {
            // Try memory fallback first (populated by cache warmup)
            try {
                @SuppressWarnings("unchecked")
                List<RiskScore> allScores = (List<RiskScore>) cacheWarmupService.getFallback("allRiskScores");
                if (allScores != null) {
                    country = allScores.stream()
                            .filter(s -> iso3.equalsIgnoreCase(s.getIso3()))
                            .findFirst().orElse(null);
                }
            } catch (Exception e) {
                log.debug("Memory fallback unavailable for risk scores: {}", e.getMessage());
            }
            // Fallback: calculate directly (only if warmup hasn't run yet)
            if (country == null) {
                try {
                    country = riskScoreService.calculateRiskScore(iso2);
                } catch (Exception e) {
                    log.warn("Risk score calculation failed for {}: {}", iso2, e.getMessage());
                }
            }
        }
        if (country == null) return null;

        CountryDataPackResult result = new CountryDataPackResult();
        result.riskLevel = country.getRiskLevel();
        result.riskScore = country.getScore();

        StringBuilder pack = new StringBuilder();
        List<QASource> sources = new ArrayList<>();
        int sourceIdx = 0;

        // === RISK PROFILE (same as buildCountryDataPack) ===
        pack.append("## ").append(country.getCountryName().toUpperCase()).append(" RISK PROFILE\n");
        pack.append(String.format("Overall Score: %d/100 (%s)\n", country.getScore(), country.getRiskLevel()));
        pack.append(String.format("Confidence: %.0f%%\n", country.getConfidence() * 100));
        pack.append(String.format("Horizon: %s - %s\n\n", country.getHorizon(), country.getHorizonReason()));

        pack.append("### INDICATOR BREAKDOWN\n");
        pack.append(String.format("- Climate: %d/100 (Precip anomaly: %.1f%%)\n",
                country.getClimateScore(),
                country.getPrecipitationAnomaly() != null ? country.getPrecipitationAnomaly() : 0));
        pack.append(String.format("- Conflict: %d/100 (GDELT z-score: %.1f)\n",
                country.getConflictScore(),
                country.getGdeltZScore() != null ? country.getGdeltZScore() : 0));
        pack.append(String.format("- Economic: %d/100 (Currency 30d: %.1f%%)\n",
                country.getEconomicScore(),
                country.getCurrencyChange30d() != null ? country.getCurrencyChange30d() : 0));
        pack.append(String.format("- Food Security: %d/100 (IPC Phase: %.0f)\n",
                country.getFoodSecurityScore(),
                country.getIpcPhase() != null ? country.getIpcPhase() : 0));

        pack.append("\n### ELEVATED INDICATORS\n");
        if (country.isClimateElevated()) pack.append("- Climate: ELEVATED\n");
        if (country.isConflictElevated()) pack.append("- Conflict: ELEVATED\n");
        if (country.isEconomicElevated()) pack.append("- Economic: ELEVATED\n");
        pack.append(String.format("Confirmation: %d/3 elevated\n", country.getElevatedCount()));

        pack.append("\n### PRIMARY DRIVERS\n");
        if (country.getDrivers() != null) {
            for (String driver : country.getDrivers()) {
                pack.append("- ").append(driver).append("\n");
            }
        }

        // === REGIONAL PEERS ===
        try {
            @SuppressWarnings("unchecked")
            List<RiskScore> allScores = (List<RiskScore>) cacheWarmupService.getFallback("allRiskScores");
            if (allScores != null && !allScores.isEmpty()) {
                pack.append("\n### REGIONAL CONTEXT\n");
                String region = getRegion(iso3);
                String peersStr = allScores.stream()
                        .filter(s -> region.equals(getRegion(s.getIso3())))
                        .filter(s -> !iso3.equalsIgnoreCase(s.getIso3()))
                        .sorted((a, b) -> b.getScore() - a.getScore())
                        .limit(3)
                        .map(s -> String.format("%s(%d)", s.getCountryName(), s.getScore()))
                        .collect(Collectors.joining(", "));
                pack.append("Regional peers: ").append(peersStr).append("\n");
            }
        } catch (Exception e) {
            log.debug("Regional peers cache not ready: {}", e.getMessage());
        }

        // === CRISIS CONTEXT (verified intelligence for post-cutoff events) ===
        String crisisContext = CRISIS_CONTEXT.get(iso3);
        if (crisisContext != null) {
            pack.append("\n### CRISIS CONTEXT (verified current intelligence — use this as ground truth)\n");
            pack.append(crisisContext).append("\n");
        }

        // === PREPARED INTELLIGENCE (enriched articles from daily prep pipeline) ===
        try {
            IntelligencePrepService.PreparedIntelligence preparedIntel =
                    intelligencePrepService.getIntelligence(iso3);
            if (preparedIntel != null && preparedIntel.articleCount > 0) {
                pack.append(preparedIntel.toDataPackSection());
            }
        } catch (Exception e) {
            log.debug("Prepared intelligence not available for {}: {}", iso3, e.getMessage());
        }

        // === NUMBERED SOURCES (for narrative citations) ===
        pack.append("\n### SOURCES\n");

        String countryName = MonitoredCountries.getName(iso3);

        // Fetch Google News + ReliefWeb in parallel
        CompletableFuture<List<Headline>> googleNewsFuture = CompletableFuture.supplyAsync(() -> {
            try { return fetchGoogleNewsHeadlines(countryName, 5); }
            catch (Exception e) { return Collections.<Headline>emptyList(); }
        });
        CompletableFuture<List<Headline>> reliefWebFuture = CompletableFuture.supplyAsync(() -> {
            try { return reliefWebService.getLatestReportsAsHeadlines(iso3, 5, 7); }
            catch (Exception e) { return Collections.<Headline>emptyList(); }
        });

        try {
            CompletableFuture.allOf(googleNewsFuture, reliefWebFuture).join();
        } catch (Exception e) {
            log.debug("Parallel news fetch error: {}", e.getMessage());
        }

        // 1. Google News (top 3 fresh articles)
        try {
            List<Headline> googleNews = googleNewsFuture.join();
            for (Headline h : googleNews) {
                if (sourceIdx >= 3) break;  // Max 3 from Google News
                sourceIdx++;
                sources.add(QASource.builder()
                        .index(sourceIdx)
                        .title(h.getTitle())
                        .url(h.getUrl())
                        .source(h.getSource())
                        .sourceType("NEWS")
                        .build());
                pack.append(String.format("[%d] %s — %s\n", sourceIdx, h.getTitle(),
                        h.getSource() != null ? h.getSource() : "Google News"));
            }
        } catch (Exception e) {
            log.debug("Google News result error: {}", e.getMessage());
        }

        // 2. ReliefWeb humanitarian reports (top 3)
        try {
            List<Headline> reports = reliefWebFuture.join();
            if (reports != null) {
                for (Headline r : reports) {
                    if (sourceIdx >= 8) break;  // Max 8 total sources
                    sourceIdx++;
                    sources.add(QASource.builder()
                            .index(sourceIdx)
                            .title(r.getTitle())
                            .url(r.getUrl())
                            .source(r.getSource() != null ? r.getSource() : "ReliefWeb")
                            .sourceType("RELIEFWEB")
                            .build());
                    pack.append(String.format("[%d] %s — %s\n", sourceIdx, r.getTitle(),
                            r.getSource() != null ? r.getSource() : "ReliefWeb"));
                }
            }
        } catch (Exception e) {
            log.debug("ReliefWeb result error: {}", e.getMessage());
        }

        // 3. News feed cache (GDELT + RSS items for this country)
        try {
            StoryService.NewsFeedData feed = storyService.getNewsFeed(null, null);
            if (feed != null) {
                List<NewsItem> countryItems = new ArrayList<>();
                if (feed.getMedia() != null) {
                    feed.getMedia().stream()
                            .filter(item -> iso3.equalsIgnoreCase(item.getCountry()))
                            .limit(3)
                            .forEach(countryItems::add);
                }
                for (NewsItem item : countryItems) {
                    if (sourceIdx >= 10) break;
                    sourceIdx++;
                    sources.add(QASource.builder()
                            .index(sourceIdx)
                            .title(item.getTitle())
                            .url(item.getUrl())
                            .source(item.getSource())
                            .sourceType(item.getSourceType())
                            .timeAgo(item.getTimeAgo())
                            .build());
                    pack.append(String.format("[%d] %s — %s\n", sourceIdx, item.getTitle(),
                            item.getSource() != null ? item.getSource() : item.getSourceType()));
                }
            }
        } catch (Exception e) {
            log.debug("News feed cache error for {}: {}", iso3, e.getMessage());
        }

        result.dataPack = pack.toString();
        result.sources = sources;
        return result;
    }

    /**
     * Fetch recent news headlines from Google News RSS for a country.
     * Free, no API key, always up-to-date. Returns Headline objects with title, url, source.
     */
    private List<Headline> fetchGoogleNewsHeadlines(String countryName, int limit) {
        try {
            String query = URLEncoder.encode(countryName, StandardCharsets.UTF_8);
            String url = "https://www.bing.com/news/search?q=" + query + "&format=rss&mkt=en-US";

            String xml = rssClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (xml == null || xml.isBlank()) {
                log.warn("Bing News RSS returned empty for {}", countryName);
                return Collections.emptyList();
            }
            log.debug("Bing News RSS response length for {}: {} chars", countryName, xml.length());

            XmlMapper xmlMapper = new XmlMapper();
            xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            NewsAggregatorService.RssFeed feed = xmlMapper.readValue(xml, NewsAggregatorService.RssFeed.class);

            if (feed == null || feed.getChannel() == null || feed.getChannel().getItems() == null) {
                return Collections.emptyList();
            }

            Set<String> seen = new HashSet<>();
            List<Headline> headlines = new ArrayList<>();

            for (NewsAggregatorService.RssItem item : feed.getChannel().getItems()) {
                if (headlines.size() >= limit) break;
                String title = item.getTitle();
                if (title == null || title.isBlank()) continue;

                // Bing News titles are clean (no " - Source" suffix like Google News)
                String cleanTitle = title.trim();
                String sourceName = "Bing News";

                // Some Bing items may still have " - Source" suffix
                int dashIdx = cleanTitle.lastIndexOf(" - ");
                if (dashIdx > 0 && dashIdx > cleanTitle.length() - 40) {
                    sourceName = cleanTitle.substring(dashIdx + 3).trim();
                    cleanTitle = cleanTitle.substring(0, dashIdx).trim();
                }
                if (cleanTitle.length() > 120) cleanTitle = cleanTitle.substring(0, 117) + "...";

                // Dedup by first 40 chars
                String key = cleanTitle.substring(0, Math.min(40, cleanTitle.length())).toLowerCase();
                if (seen.add(key)) {
                    headlines.add(Headline.builder()
                            .title(cleanTitle)
                            .url(item.getLink())
                            .source(sourceName)
                            .date(item.getPubDate())
                            .build());
                }
            }

            log.info("Fetched {} Bing News headlines for {}", headlines.size(), countryName);
            return headlines;

        } catch (Exception e) {
            log.warn("Bing News RSS failed for {}: {}", countryName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Build regional data pack for a specific region (Africa, LAC, MENA, Asia, Europe)
     */
    private String buildRegionalDataPack(String region) {
        List<RiskScore> allScores = riskScoreService.getAllRiskScores();

        // Filter scores by region
        List<RiskScore> regionalScores = allScores.stream()
                .filter(s -> region.equalsIgnoreCase(getRegion(s.getIso3())))
                .sorted((a, b) -> b.getScore() - a.getScore())
                .toList();

        if (regionalScores.isEmpty()) {
            log.warn("No risk scores found for region: {}", region);
            return null;
        }

        StringBuilder pack = new StringBuilder();
        pack.append("# REGIONAL INTELLIGENCE BRIEFING: ").append(region.toUpperCase()).append("\n\n");

        // Regional summary
        int criticalCount = (int) regionalScores.stream().filter(s -> "CRITICAL".equals(s.getRiskLevel())).count();
        int alertCount = (int) regionalScores.stream().filter(s -> "ALERT".equals(s.getRiskLevel())).count();
        int warningCount = (int) regionalScores.stream().filter(s -> "WARNING".equals(s.getRiskLevel())).count();
        double avgScore = regionalScores.stream().mapToInt(RiskScore::getScore).average().orElse(0);

        pack.append("## REGIONAL OVERVIEW\n");
        pack.append(String.format("- Countries monitored: %d\n", regionalScores.size()));
        pack.append(String.format("- CRITICAL: %d | ALERT: %d | WARNING: %d\n", criticalCount, alertCount, warningCount));
        pack.append(String.format("- Average risk score: %.0f/100\n\n", avgScore));

        // Top risk countries (top 5)
        pack.append("## TOP RISK COUNTRIES\n");
        regionalScores.stream().limit(5).forEach(s -> {
            pack.append(String.format("### %s (%s) - Score: %d/100 [%s]\n",
                    s.getCountryName(), s.getIso3(), s.getScore(), s.getRiskLevel()));
            pack.append(String.format("  - Climate: %d | Conflict: %d | Economic: %d | Food: %d\n",
                    s.getClimateScore(), s.getConflictScore(), s.getEconomicScore(), s.getFoodSecurityScore()));
            if (s.getDrivers() != null && !s.getDrivers().isEmpty()) {
                pack.append("  - Drivers: ").append(String.join(", ", s.getDrivers())).append("\n");
            }
            pack.append("  - Trend: ").append(s.getTrend() != null ? s.getTrend() : "stable").append("\n\n");
        });

        // Common drivers across region
        pack.append("## DOMINANT REGIONAL DRIVERS\n");
        Map<String, Long> driverCounts = regionalScores.stream()
                .filter(s -> s.getDrivers() != null)
                .flatMap(s -> s.getDrivers().stream())
                .collect(Collectors.groupingBy(d -> d, Collectors.counting()));

        driverCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(4)
                .forEach(e -> pack.append(String.format("- %s: affects %d countries\n", e.getKey(), e.getValue())));

        // Add cluster alerts for region
        pack.append("\n## CLUSTER CONVERGENCE\n");
        List<RegionalClusterService.ClusterAlert> clusters = regionalClusterService.analyzeRegionalClusters(regionalScores);
        if (clusters.isEmpty()) {
            pack.append("No significant cluster convergence detected.\n");
        } else {
            clusters.forEach(c -> pack.append(String.format("- %s: %s - %s\n",
                    c.getClusterName(), c.getStatus(), c.getDescription())));
        }

        // Recent news for top countries
        pack.append("\n## RECENT DEVELOPMENTS (GDELT)\n");
        List<MediaSpike> spikes = gdeltService.getAllConflictSpikes();
        regionalScores.stream().limit(3).forEach(country -> {
            spikes.stream()
                    .filter(s -> country.getIso3().equalsIgnoreCase(s.getIso3()))
                    .findFirst()
                    .ifPresent(s -> {
                        pack.append(String.format("\n**%s:**\n", country.getCountryName()));
                        if (s.getTopHeadlines() != null) {
                            s.getTopHeadlines().stream().limit(2).forEach(h ->
                                    pack.append("  - ").append(h).append("\n"));
                        }
                    });
        });

        return pack.toString();
    }

    /**
     * Build regional analysis prompt - intelligence briefing style
     */
    private String buildRegionalPrompt(String dataPack, String region) {
        String regionName = switch (region.toLowerCase()) {
            case "africa" -> "Africa (Sub-Saharan)";
            case "mena" -> "Middle East & North Africa";
            case "lac", "americas" -> "Latin America & Caribbean";
            case "asia" -> "Asia-Pacific";
            case "europe" -> "Eastern Europe";
            default -> region;
        };

        return String.format("""
            You are a senior humanitarian intelligence analyst producing a MORNING BRIEF for %s.

            Your audience: Decision-makers at WFP, UNHCR, OCHA who need to understand what's happening in the region NOW.

            ## DATA PACKAGE
            %s

            ## YOUR TASK
            Produce a 150-200 word INTELLIGENCE BRIEF covering:

            1. **SITUATION SUMMARY** (2-3 sentences): The overall state of the region - is it stable, deteriorating, or critical? What's the dominant narrative?

            2. **KEY DEVELOPMENTS** (3-4 bullet points): The 3-4 most important things happening RIGHT NOW. Focus on changes, escalations, or emerging patterns.

            3. **SPILLOVER RISKS**: Any cross-border dynamics or regional contagion risks. How are crises in one country affecting neighbors?

            4. **WATCH LIST**: 1-2 countries or situations to monitor closely in the next 7 days.

            ## STYLE REQUIREMENTS
            - Write like a State Department intelligence brief - professional, precise, no speculation
            - Lead with the most important information
            - Use specific numbers when available
            - No emojis, no markdown headers in output
            - Focus on "so what" - why should the reader care?

            Respond with ONLY the brief - no preamble, no explanation.
            """, regionName, dataPack);
    }

    /**
     * Call Claude for regional analysis
     */
    private AIAnalysis callClaudeRegional(String prompt, String region) {
        try {
            String systemPrompt = "You are a humanitarian intelligence analyst providing regional situation briefs. Be concise, data-driven, and actionable.";

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("max_tokens", 1000);
            requestBody.put("system", systemPrompt);

            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);
            requestBody.set("messages", messages);

            String response = webClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            if (response == null) {
                throw new RuntimeException("Empty response from Claude API");
            }

            JsonNode responseJson = objectMapper.readTree(response);
            String content = responseJson.path("content").get(0).path("text").asText();

            return AIAnalysis.builder()
                    .scope("region")
                    .region(region)
                    .summary(content)
                    .keyFindings(List.of(content)) // Put full brief in findings
                    .drivers(List.of())
                    .watchList(List.of())
                    .generatedAt(LocalDateTime.now())
                    .model(model)
                    .fromCache(false)
                    .build();

        } catch (Exception e) {
            log.error("Error calling Claude for regional analysis: {}", e.getMessage());
            return AIAnalysis.builder()
                    .scope("region")
                    .region(region)
                    .summary("Analysis generation failed: " + e.getMessage())
                    .keyFindings(List.of("Error generating regional brief"))
                    .drivers(List.of())
                    .watchList(List.of())
                    .generatedAt(LocalDateTime.now())
                    .model(model)
                    .fromCache(false)
                    .build();
        }
    }

    private String buildGlobalPrompt(String dataPack, NewsSignal newsSignal) {
        // Build news signal context for the prompt
        String newsContext = "";
        if (newsSignal != null && newsSignal.getHeadlines() != null && !newsSignal.getHeadlines().isEmpty()) {
            StringBuilder news = new StringBuilder();
            news.append("\n## NEWS & INTELLIGENCE SIGNAL (").append(newsSignal.getCountryName()).append(")\n");
            news.append("Media Level: ").append(newsSignal.getLevel());
            news.append(" | Risk Level: ").append(newsSignal.getRiskLevel());
            if (newsSignal.getConvergenceTag() != null) {
                news.append(" | ").append(newsSignal.getConvergenceTag());
            }
            news.append("\nMedia spike: ").append(newsSignal.getSpikeStat()).append("\n");
            news.append("Media headlines (GDELT):\n");
            for (Headline h : newsSignal.getHeadlines()) {
                news.append("- ").append(h.getTitle()).append("\n");
            }

            // Add ReliefWeb official reports
            if (newsSignal.getHumanitarianReports() != null && !newsSignal.getHumanitarianReports().isEmpty()) {
                news.append("\nOfficial humanitarian reports (ReliefWeb - UN/NGO sources):\n");
                for (Headline h : newsSignal.getHumanitarianReports()) {
                    news.append("- ").append(h.getSource() != null ? h.getSource() + ": " : "")
                            .append(h.getTitle()).append("\n");
                }
            }

            newsContext = news.toString();
        }

        return """
            You are a humanitarian early warning analyst writing a risk brief.
            Your job: RANK humanitarian risk by priority for decision-makers.

            STRICT RULES:
            1. ONLY cite: IPC Phase, Risk Score, Inflation %%, NDVI level, IDPs, people IPC 3+
            2. NEVER use internal scores, z-scores, article counts, or hidden metrics
            3. ONLY cite IPC Phase for a country if it's EXPLICITLY stated in the data
            4. Use STRONG verbs: "highest", "largest", "converging" — NOT "shows", "faces", "demonstrates"
            5. Use RISK language, not commands: "high risk of X" NOT "X required" or "X needed"
            6. Use INSTITUTIONAL language: neutral, analytical, policy-appropriate

            DATA SNAPSHOT:
            %s
            %s

            ANALYSIS STRUCTURE:

            1. KEY FINDINGS (5 items) — ranked by severity
            Format: "[Ranking insight]. Evidence: [metric] [value]; [metric] [value]. Confidence: High/Medium"

            CORRECT: "Sudan has the highest humanitarian severity. Evidence: IPC Phase 5 (Famine); 24.6M people IPC 3+; 9.6M IDPs. Confidence: High"
            CORRECT: "Horn of Africa triangle converging on high-risk signals. Evidence: Ethiopia Risk Score 78; Somalia Risk Score 76 (IPC Phase 4); Yemen Risk Score 76 (IPC Phase 4). Confidence: Medium"

            WRONG: "all reaching IPC Phase 4" ← only say this if ALL countries have explicit IPC Phase 4 in data

            2. PRIMARY DRIVERS (3 items) — explain the WHY
            Format: "[Driver]: [countries] share [specific pattern with numbers]"

            CORRECT: "Economic collapse trajectory: Sudan (138.8%% inflation), South Sudan (91.4%% inflation) driving food access crisis"
            CORRECT: "Climate-food nexus: Afghanistan, Somalia combine NDVI drought stress with IPC Phase 4"

            3. NEAR-TERM RISK OUTLOOK (30 days) — Top 3 with explicit priority level
            Risk outlook based on current indicator trends and composite early-warning signals.
            Format: "[PRIORITY LEVEL]: [Country] — [evidence] — [risk statement]"

            Priority levels:
            - CRITICAL = immediate severity (famine, mass displacement)
            - IMMEDIATE = escalation trajectory (economic collapse, conflict spike)
            - SCALE = large affected population needing response

            LANGUAGE EXAMPLES (use institutional, neutral tone):
            CORRECT: "CRITICAL: Sudan — IPC Phase 5 + 9.6M IDPs + 138.8%% inflation — high risk of geographic expansion of famine conditions and increased cross-border displacement pressure"
            CORRECT: "IMMEDIATE: South Sudan — Risk Score 91 + 91.4%% inflation — macroeconomic deterioration within the upcoming lean season increasing risk of transition to famine conditions"
            CORRECT: "SCALE: DR Congo — 27.7M people IPC 3+ + 5.3M IDPs — crisis scale may exceed current response capacity"

            WRONG: "containment required" or "immediate action needed" ← too commanding
            WRONG: "may trigger famine transition" ← use "increasing risk of transition to famine conditions"
            WRONG: "response capacity gap" ← use "crisis scale may exceed current response capacity"
            WRONG: "cross-border displacement flows" ← use "cross-border displacement pressure"
            WRONG: "within seasonal cycle" ← use "within the upcoming lean season"

            4. END with global context (add to last driver):
            "Priority concentration: [Top 3 countries] account for [X]M people in IPC 3+."

            5. OPERATIONAL INSIGHT (1 sentence) — ONLY if NEWS & INTELLIGENCE SIGNAL section is present above
            One sentence on whether media coverage AND official humanitarian reporting confirm or contradict the risk indicators.
            Consider both GDELT media headlines and ReliefWeb official reports when assessing convergence/divergence.
            Examples:
            - "Operational reporting confirms rapid deterioration; media attention aligns with severity indicators."
            - "Limited official reporting despite high risk suggests potential monitoring gap."
            - "Media escalation precedes formal humanitarian response mobilization."
            If no news signal data, set operationalInsight to null.

            RESPOND IN THIS EXACT JSON:
            {
              "keyFindings": ["finding1", "finding2", "finding3", "finding4", "finding5"],
              "drivers": ["driver1", "driver2", "driver3 + priority concentration statement"],
              "watchList": ["CRITICAL: country1...", "IMMEDIATE: country2...", "SCALE: country3..."],
              "operationalInsight": "One sentence if news signal present, otherwise null"
            }

            Write like an analyst briefing decision-makers. Risk language, not commands. Institutional tone.
            """.formatted(dataPack, newsContext);
    }

    /**
     * Narrative prompt for country analysis with inline citations.
     * Scales output length by severity: STABLE=short, CRITICAL=detailed.
     */
    private String buildCountryNarrativePrompt(String dataPack, String countryName, String riskLevel, int riskScore) {
        String lengthGuidance;
        if (riskScore >= 70) {
            lengthGuidance = "HIGH SEVERITY — provide detailed analysis in each section.";
        } else if (riskScore >= 40) {
            lengthGuidance = "MODERATE RISK — keep analysis focused on key drivers and trajectory.";
        } else {
            lengthGuidance = "LOW RISK — keep each section concise, 1-2 sentences per section.";
        }

        return String.format("""
            Produce a country risk brief for %s. %s

            You have the sensor data below AND your own knowledge of the current geopolitical situation. \
            START with the most important real-world context — if there is an active war, coup, famine, \
            or major crisis, LEAD with that. The news headlines in the sources tell you what is happening NOW. \
            Do not just describe numbers — explain what they MEAN in context.

            If the data includes a CRISIS CONTEXT section, treat it as verified ground truth — this is \
            confirmed intelligence about the current situation. Lead your analysis with this context. \
            If the data includes a CURRENT INTELLIGENCE section, use the article snippets to inform \
            your analysis with specific recent developments, facts, and details. \
            Integrate the sensor data and news sources as supporting evidence.

            Cite sources as [1], [2], etc. where relevant. Institutional tone. No markdown, no bullet points, \
            no numbered lists. Never start with "Based on the data".

            DATA:
            %s

            YOUR OUTPUT MUST USE EXACTLY THIS FORMAT with these 4 section headers. \
            Each header must appear on its own line, followed by a colon and space, then the content. \
            Do not omit or rename any section.

            BOTTOM LINE: One to two sentences — the single most critical takeaway for a decision-maker. \
            Lead with the real-world situation, not the score.

            CURRENT SITUATION: What is actually happening on the ground — the real-world crisis first, \
            then supporting data (risk score, indicators, recent developments with citations [N]). \
            Compare to regional peers.

            KEY RISKS: The 2-3 most critical risk factors woven into analytical prose. \
            Connect to evidence and explain cascading effects.

            OUTLOOK: 30-day forward assessment. Most likely trajectory, escalation/de-escalation triggers, \
            what to monitor.""",
                countryName, lengthGuidance, dataPack);
    }

    /**
     * Call Claude for narrative country analysis.
     * Returns AIAnalysis with narrative field populated instead of keyFindings/drivers/watchList.
     */
    private AIAnalysis callClaudeNarrative(String prompt, String iso3, String countryName, List<QASource> sources) {
        if (apiKey == null || apiKey.isBlank()) {
            return AIAnalysis.builder()
                    .scope("country")
                    .countryIso3(iso3)
                    .countryName(countryName)
                    .narrative("AI analysis is not configured. Set anthropic.api.key to enable narrative briefings.")
                    .sources(sources)
                    .generatedAt(LocalDateTime.now())
                    .model(model)
                    .fromCache(false)
                    .build();
        }

        try {
            String systemMsg = "You are a senior intelligence analyst producing crisis briefs. " +
                    "If the data includes a CRISIS CONTEXT section, treat it as verified ground truth and lead with it. " +
                    "Lead with real-world context — wars, coups, famines, geopolitical events — not scores or indicators. " +
                    "Structure every response with exactly 4 sections: BOTTOM LINE:, CURRENT SITUATION:, KEY RISKS:, OUTLOOK:. " +
                    "Each header on its own line followed by content. Write in analytical prose, no bullet points.";

            // Prefill assistant response to force structured format
            Map<String, Object> request = Map.of(
                    "model", model,
                    "max_tokens", 1500,
                    "system", systemMsg,
                    "messages", List.of(
                            Map.of("role", "user", "content", prompt),
                            Map.of("role", "assistant", "content", "BOTTOM LINE:")
                    )
            );

            String response = webClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(45))
                    .block();

            JsonNode root = objectMapper.readTree(response);
            String rawNarrative = root.path("content").get(0).path("text").asText();
            // Prepend "BOTTOM LINE:" since it was the prefilled assistant content (not in response)
            String narrative = "BOTTOM LINE:" + rawNarrative;

            return AIAnalysis.builder()
                    .scope("country")
                    .countryIso3(iso3)
                    .countryName(countryName)
                    .narrative(narrative)
                    .sources(sources)
                    .generatedAt(LocalDateTime.now())
                    .model(model)
                    .fromCache(false)
                    .build();

        } catch (Exception e) {
            log.error("Claude narrative call failed for {}: {}", iso3, e.getMessage());
            return AIAnalysis.builder()
                    .scope("country")
                    .countryIso3(iso3)
                    .countryName(countryName)
                    .narrative("Unable to generate analysis. Please try again.")
                    .sources(sources)
                    .generatedAt(LocalDateTime.now())
                    .model(model)
                    .fromCache(false)
                    .build();
        }
    }

    /** Legacy country prompt — kept for deep analysis backward compatibility */
    private String buildCountryPrompt(String dataPack, String countryName) {
        return """
            You are a senior humanitarian crisis analyst. Analyze %s using ONLY the data below.

            STRICT RULES:
            1. ONLY use facts from the DATA section - no external information
            2. Cite specific numbers for every claim
            3. Compare to regional peers shown in the data
            4. If data is missing, say "not in snapshot"

            DATA SNAPSHOT:
            %s

            FORMAT: Each finding must include "Evidence: [metric]=[value]"

            Respond in this EXACT JSON:
            {
              "keyFindings": ["finding1 with evidence", "finding2 with evidence", "finding3 with evidence", "finding4 with evidence", "finding5 with evidence"],
              "drivers": ["driver1 with metrics", "driver2 with metrics", "driver3 with metrics"],
              "watchList": ["watch1 with reasoning", "watch2 with reasoning", "watch3 with reasoning"]
            }

            FOCUS ON:
            - Which indicators are ELEVATED (marked in data)
            - The 2-of-3 confirmation status
            - Comparison with regional peers listed
            - The stated horizon and reasoning
            - Recent headlines if provided

            Cite only what appears in the data. No hallucinations.
            """.formatted(countryName, dataPack);
    }

    private AIAnalysis callClaude(String prompt, String scope, String iso3, String countryName) {
        return callClaude(prompt, scope, iso3, countryName, null);
    }

    private AIAnalysis callClaude(String prompt, String scope, String iso3, String countryName, NewsSignal newsSignal) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Anthropic API key not configured, returning mock analysis");
            return mockAnalysis(scope, iso3, countryName);
        }

        try {
            Map<String, Object> request = Map.of(
                    "model", model,
                    "max_tokens", 1024,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            );

            String response = webClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            return parseClaudeResponse(response, scope, iso3, countryName, newsSignal);

        } catch (Exception e) {
            log.error("Claude API call failed: {}", e.getMessage());
            return mockAnalysis(scope, iso3, countryName);
        }
    }

    private AIAnalysis parseClaudeResponse(String response, String scope, String iso3, String countryName, NewsSignal newsSignal) {
        try {
            JsonNode root = objectMapper.readTree(response);
            String content = root.path("content").get(0).path("text").asText();

            // Extract JSON from response (Claude might wrap it in markdown)
            String json = content;
            if (content.contains("```json")) {
                json = content.substring(content.indexOf("```json") + 7);
                json = json.substring(0, json.indexOf("```"));
            } else if (content.contains("{")) {
                json = content.substring(content.indexOf("{"));
                json = json.substring(0, json.lastIndexOf("}") + 1);
            }

            JsonNode analysis = objectMapper.readTree(json);

            List<String> findings = new ArrayList<>();
            analysis.path("keyFindings").forEach(n -> findings.add(n.asText()));

            List<String> drivers = new ArrayList<>();
            analysis.path("drivers").forEach(n -> drivers.add(n.asText()));

            List<String> watchList = new ArrayList<>();
            analysis.path("watchList").forEach(n -> watchList.add(n.asText()));

            // Extract operational insight for news signal
            String operationalInsight = null;
            if (analysis.has("operationalInsight") && !analysis.path("operationalInsight").isNull()) {
                operationalInsight = analysis.path("operationalInsight").asText();
                if (operationalInsight != null && !operationalInsight.isBlank() && !"null".equals(operationalInsight)) {
                    // Set on the news signal if present
                    if (newsSignal != null) {
                        newsSignal.setOperationalInsight(operationalInsight);
                    }
                }
            }

            return AIAnalysis.builder()
                    .scope(scope)
                    .countryIso3(iso3)
                    .countryName(countryName)
                    .keyFindings(findings)
                    .drivers(drivers)
                    .watchList(watchList)
                    .generatedAt(LocalDateTime.now())
                    .model(model)
                    .fromCache(false)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse Claude response: {}", e.getMessage());
            return mockAnalysis(scope, iso3, countryName);
        }
    }

    private AIAnalysis mockAnalysis(String scope, String iso3, String countryName) {
        return AIAnalysis.builder()
                .scope(scope)
                .countryIso3(iso3)
                .countryName(countryName)
                .keyFindings(List.of(
                        "Configure anthropic.api.key in application.properties to enable AI analysis",
                        "Mock data: Multiple countries show converging climate-conflict stress patterns",
                        "Mock data: Currency instability correlating with food insecurity in Sahel region",
                        "Mock data: Regional spillover effects visible from Sudan to Chad and South Sudan",
                        "Mock data: Economic pressure building in East Africa following drought conditions"
                ))
                .drivers(List.of(
                        "Configure API key for real analysis",
                        "Mock driver: Climate-induced displacement",
                        "Mock driver: Cross-border conflict dynamics"
                ))
                .watchList(List.of(
                        "Set anthropic.api.key property",
                        "Mock: Monitor rainy season developments",
                        "Mock: Track currency movements"
                ))
                .generatedAt(LocalDateTime.now())
                .model(model + " (mock)")
                .fromCache(false)
                .build();
    }

    private String getCountryName(String iso3) {
        List<RiskScore> scores = riskScoreService.getAllRiskScores();
        return scores.stream()
                .filter(s -> iso3.equalsIgnoreCase(s.getIso3()))
                .findFirst()
                .map(RiskScore::getCountryName)
                .orElse(iso3);
    }

    private String getRegion(String iso3) {
        // Region mapping aligned with MonitoredCountries.COUNTRY_REGIONS
        Set<String> africa = Set.of("SDN", "SSD", "SOM", "ETH", "COD", "CAF", "NGA", "MLI", "NER", "BFA", "TCD", "MOZ", "ZWE", "KEN", "UGA", "RWA", "BDI", "CMR");
        Set<String> mena = Set.of("YEM", "SYR", "LBY", "LBN", "IRQ", "PSE");
        Set<String> asia = Set.of("AFG", "PAK", "MMR", "BGD");
        Set<String> lac = Set.of("HTI", "VEN", "COL", "PER", "ECU", "GTM", "HND", "SLV", "NIC", "MEX", "CUB", "PAN");
        Set<String> europe = Set.of("UKR");

        if (africa.contains(iso3)) return "africa";
        if (mena.contains(iso3)) return "mena";
        if (asia.contains(iso3)) return "asia";
        if (lac.contains(iso3)) return "lac";
        if (europe.contains(iso3)) return "europe";
        return "other";
    }

    // ========================================
    // DEEP ANALYSIS - CONTEXTUAL SCORING
    // ========================================

    /**
     * Deep contextual analysis with dynamic weighting.
     * Claude acts as the reasoning engine, determining weights based on context.
     * Single call - meant to be triggered by user button for cost control.
     */
    public DeepAnalysisResult deepAnalyze(String iso3) {
        log.info("Starting deep contextual analysis for {}", iso3);
        long start = System.currentTimeMillis();

        if (apiKey == null || apiKey.isBlank()) {
            return DeepAnalysisResult.builder()
                    .iso3(iso3)
                    .countryName(getCountryName(iso3))
                    .score(0)
                    .reasoning("API key not configured. Please set anthropic.api.key in application.properties")
                    .build();
        }

        // Build comprehensive data package
        String dataPack = buildDeepDataPack(iso3);
        if (dataPack == null) {
            return DeepAnalysisResult.builder()
                    .iso3(iso3)
                    .countryName(iso3)
                    .score(0)
                    .reasoning("Country not found in monitored list")
                    .build();
        }

        String prompt = buildDeepAnalysisPrompt(dataPack, getCountryName(iso3));

        try {
            Map<String, Object> request = Map.of(
                    "model", model,
                    "max_tokens", 2048,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            );

            String response = webClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(45))
                    .block();

            DeepAnalysisResult result = parseDeepAnalysisResponse(response, iso3);
            result.setDurationMs(System.currentTimeMillis() - start);
            result.setModel(model);

            log.info("Deep analysis completed for {} in {}ms", iso3, result.getDurationMs());
            return result;

        } catch (Exception e) {
            log.error("Deep analysis failed for {}: {}", iso3, e.getMessage());
            return DeepAnalysisResult.builder()
                    .iso3(iso3)
                    .countryName(getCountryName(iso3))
                    .score(0)
                    .reasoning("Analysis failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Build comprehensive data pack for deep analysis.
     * Organized by data freshness: REALTIME → RECENT → BASELINE → STRUCTURAL
     * Token-efficient format optimized for Claude.
     */
    @SuppressWarnings("unchecked")
    private String buildDeepDataPack(String iso3) {
        // Use cached scores (from warmup) — never trigger expensive on-demand recalculation
        List<RiskScore> scores = cacheWarmupService != null
                ? (List<RiskScore>) cacheWarmupService.getFallback("allRiskScores")
                : null;
        if (scores == null || scores.isEmpty()) {
            scores = riskScoreService.getAllRiskScores();
        }
        RiskScore country = scores.stream()
                .filter(s -> iso3.equalsIgnoreCase(s.getIso3()))
                .findFirst()
                .orElse(null);

        if (country == null) return null;

        StringBuilder pack = new StringBuilder();
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        // === HEADER ===
        pack.append("# ").append(country.getCountryName().toUpperCase())
            .append(" (").append(iso3).append(") — Analysis: ").append(now).append(" UTC\n\n");

        // === SECTION 1: REAL-TIME SIGNALS (updated within hours) ===
        pack.append("## REALTIME SIGNALS [use for current situation]\n");

        // GDELT - use cached bulk data (avoid per-country API call that can timeout)
        try {
            MediaSpike spike = null;
            List<MediaSpike> cachedSpikes = cacheWarmupService != null
                    ? (List<MediaSpike>) cacheWarmupService.getFallback("gdeltAllSpikes") : null;
            if (cachedSpikes != null) {
                spike = cachedSpikes.stream()
                        .filter(s -> iso3.equals(s.getIso3()))
                        .findFirst().orElse(null);
            }
            // No fallback to direct API call — it can timeout (30-60s for high-volume countries)
            // If cached data isn't available yet, skip GDELT section
            if (spike != null) {
                pack.append(String.format("Media: z=%.1f (%s), %d articles/7d\n",
                    spike.getZScore() != null ? spike.getZScore() : 0,
                    spike.getSpikeLevel() != null ? spike.getSpikeLevel() : "NORMAL",
                    spike.getArticlesLast7Days() != null ? spike.getArticlesLast7Days() : 0));
                // Top 3 headlines
                if (spike.getTopHeadlines() != null && !spike.getTopHeadlines().isEmpty()) {
                    pack.append("Headlines: ");
                    pack.append(spike.getTopHeadlines().stream().limit(3)
                        .map(h -> h.length() > 80 ? h.substring(0, 77) + "..." : h)
                        .collect(Collectors.joining(" | ")));
                    pack.append("\n");
                }
            }
        } catch (Exception e) { /* skip */ }

        // Currency — use data already in RiskScore (no API call)
        if (country.getCurrencyChange30d() != null) {
            pack.append(String.format("Currency: %.1f%% (30d)\n", country.getCurrencyChange30d()));
        }

        // === SECTION 2: RECENT DATA (from cached scores) ===
        pack.append("\n## RECENT DATA [conditions this week]\n");

        // Climate — from RiskScore
        pack.append(String.format("Precip anomaly: %.1f%% vs 5yr avg\n",
            country.getPrecipitationAnomaly() != null ? country.getPrecipitationAnomaly() : 0));

        // NDVI — from memory fallback only (no API call)
        try {
            List<ClimateData> ndviData = (List<ClimateData>) cacheWarmupService.getFallback("ndviClimateData");
            if (ndviData != null) {
                ndviData.stream()
                    .filter(c -> iso3.equalsIgnoreCase(c.getIso3()))
                    .findFirst()
                    .ifPresent(c -> pack.append(String.format("NDVI drought: %s\n", c.getAlertLevel())));
            }
        } catch (Exception e) { /* skip */ }

        // IPC — from RiskScore
        if (country.getIpcPhase() != null && country.getIpcPhase() > 0) {
            pack.append(String.format("IPC Phase: %.0f\n", country.getIpcPhase()));
        }

        // === SECTION 5: COMPUTED SCORES & TRENDS ===
        pack.append("\n## RISK SCORES [computed from above]\n");
        pack.append(String.format("Static score: %d/100 (%s)\n", country.getScore(), country.getRiskLevel()));
        pack.append(String.format("Components: Climate=%d, Conflict=%d, Economic=%d, Food=%d\n",
            country.getClimateScore(), country.getConflictScore(),
            country.getEconomicScore(), country.getFoodSecurityScore()));
        pack.append(String.format("Elevated: %s%s%s (%d/3)\n",
            country.isClimateElevated() ? "Climate " : "",
            country.isConflictElevated() ? "Conflict " : "",
            country.isEconomicElevated() ? "Economic " : "",
            country.getElevatedCount()));

        // Trend data
        if (country.getPreviousScore() != null && country.getScoreDelta() != null) {
            pack.append(String.format("Trend: %s %+d pts from 7d ago (was %d)\n",
                country.getTrendIcon() != null ? country.getTrendIcon() : "→",
                country.getScoreDelta(),
                country.getPreviousScore()));
        }
        if (country.getPersistenceLabel() != null) {
            pack.append(String.format("Persistence: %s\n", country.getPersistenceLabel()));
        }

        // === SECTION 6: REGIONAL CONTEXT ===
        pack.append("\n## REGIONAL [cross-border risk]\n");
        String region = getRegion(iso3);
        List<RiskScore> peers = scores.stream()
            .filter(s -> region.equals(getRegion(s.getIso3())))
            .filter(s -> !iso3.equalsIgnoreCase(s.getIso3()))
            .sorted((a, b) -> b.getScore() - a.getScore())
            .limit(4)
            .toList();

        pack.append("Neighbors: ");
        pack.append(peers.stream()
            .map(s -> String.format("%s=%d", s.getCountryName(), s.getScore()))
            .collect(Collectors.joining(", ")));
        pack.append("\n");

        // Regional cluster alerts
        try {
            List<RegionalClusterService.ClusterAlert> clusters = regionalClusterService.analyzeRegionalClusters(scores);
            clusters.stream()
                .filter(c -> c.getAffectedCountries() != null && c.getAffectedCountries().contains(country.getCountryName()))
                .findFirst()
                .ifPresent(c -> pack.append(String.format("Cluster alert: %s - %s\n", c.getClusterName(), c.getStatus())));
        } catch (Exception e) { /* skip */ }

        return pack.toString();
    }

    private String buildDeepAnalysisPrompt(String dataPack, String countryName) {
        return """
You are a humanitarian early warning analyst. Provide contextual risk scoring for %s.

DATA FRESHNESS GUIDE:
- REALTIME: Current situation (hours old) - weight heavily
- RECENT: This week's conditions - reliable
- BASELINE: Last formal assessment (weeks/months) - may have changed
- STRUCTURAL: Annual data - background context only

CRITICAL: When REALTIME signals diverge from BASELINE, the situation is CHANGING.
Example: If baseline IPC=Phase 3 but realtime shows currency collapse + negative news spike, actual conditions are likely WORSE than baseline suggests.

DATA:
%s

OUTPUT JSON (be concise, cite numbers):
{
  "score": <0-100>,
  "riskLevel": "<CRITICAL|ALERT|WARNING|WATCH|STABLE>",
  "weights": {"climate":<0-100>,"conflict":<0-100>,"economic":<0-100>,"food":<0-100>},
  "weightReasoning": "<2 sentences: why these weights for this context>",
  "reasoning": "<3 sentences: overall assessment citing key numbers>",
  "drivers": ["<driver1: specific evidence>","<driver2>","<driver3>"],
  "trajectory": "<DETERIORATING|STABLE|IMPROVING>",
  "trajectoryReason": "<1 sentence with evidence>",
  "hotspots": ["<indicator1 to watch>","<indicator2>"],
  "confidenceLevel": "<HIGH|MEDIUM|LOW>",
  "confidenceReason": "<1 sentence on data quality>"
}

Score bands: 86-100 CRITICAL, 71-85 ALERT, 51-70 WARNING, 31-50 WATCH, 0-30 STABLE
Weights must sum to 100. Consider cascade effects and non-linear amplification.
""".formatted(countryName, dataPack);
    }

    private DeepAnalysisResult parseDeepAnalysisResponse(String response, String iso3) {
        try {
            JsonNode root = objectMapper.readTree(response);
            String content = root.path("content").get(0).path("text").asText();

            // Extract JSON from response
            String json = content;
            if (content.contains("```json")) {
                json = content.substring(content.indexOf("```json") + 7);
                json = json.substring(0, json.indexOf("```"));
            } else if (content.contains("{")) {
                json = content.substring(content.indexOf("{"));
                json = json.substring(0, json.lastIndexOf("}") + 1);
            }

            JsonNode analysis = objectMapper.readTree(json);

            // Parse weights
            Map<String, Integer> weights = new HashMap<>();
            JsonNode weightsNode = analysis.path("weights");
            weights.put("climate", weightsNode.path("climate").asInt(25));
            weights.put("conflict", weightsNode.path("conflict").asInt(25));
            weights.put("economic", weightsNode.path("economic").asInt(20));
            weights.put("food", weightsNode.path("food").asInt(30));

            // Parse drivers
            List<String> drivers = new ArrayList<>();
            analysis.path("drivers").forEach(n -> drivers.add(n.asText()));

            // Parse hotspots
            List<String> hotspots = new ArrayList<>();
            analysis.path("hotspots").forEach(n -> hotspots.add(n.asText()));

            return DeepAnalysisResult.builder()
                    .iso3(iso3)
                    .countryName(getCountryName(iso3))
                    .score(analysis.path("score").asInt())
                    .riskLevel(analysis.path("riskLevel").asText())
                    .weights(weights)
                    .weightReasoning(analysis.path("weightReasoning").asText())
                    .reasoning(analysis.path("reasoning").asText())
                    .drivers(drivers)
                    .trajectory(analysis.path("trajectory").asText())
                    .trajectoryReason(analysis.path("trajectoryReason").asText())
                    .hotspots(hotspots)
                    .confidenceLevel(analysis.path("confidenceLevel").asText())
                    .confidenceReason(analysis.path("confidenceReason").asText())
                    .generatedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse deep analysis response: {}", e.getMessage());
            return DeepAnalysisResult.builder()
                    .iso3(iso3)
                    .countryName(getCountryName(iso3))
                    .score(0)
                    .reasoning("Failed to parse analysis: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Get the top risk country ISO3 for deep analysis
     */
    public String getTopRiskCountryIso3() {
        List<RiskScore> scores = riskScoreService.getAllRiskScores();
        if (scores == null || scores.isEmpty()) return "SDN"; // Default fallback
        return scores.get(0).getIso3();
    }

    // ========================================
    // SITUATION DETECTION - CLAUDE-NATIVE
    // ========================================

    /**
     * Get cached Claude situation detection result from Redis (for page load).
     * Returns null if no cached result exists.
     */
    public SituationDetectionResult getCachedSituationResult() {
        // Try Redis first
        try {
            Object cached = redisTemplate.opsForValue().get(CLAUDE_SITUATIONS_CACHE_KEY);
            if (cached instanceof SituationDetectionResult r) {
                log.debug("Found cached Claude situations in Redis (direct)");
                return r;
            }
            if (cached instanceof java.util.Map) {
                log.debug("Found cached Claude situations in Redis (Map), converting...");
                String json = objectMapper.writeValueAsString(cached);
                return objectMapper.readValue(json, SituationDetectionResult.class);
            }
        } catch (Exception e) {
            log.debug("Redis read failed for Claude situations: {}", e.getMessage());
        }

        // Fall back to in-memory cache (works without Redis)
        if (cachedSituationInMemory != null) {
            log.debug("Returning Claude situations from in-memory cache");
            return cachedSituationInMemory;
        }

        return null;
    }

    /**
     * Save Claude situation detection result to Redis (4 hour TTL).
     */
    private void cacheSituationResult(SituationDetectionResult result) {
        // Always save in-memory (works without Redis)
        cachedSituationInMemory = result;

        try {
            redisTemplate.opsForValue().set(CLAUDE_SITUATIONS_CACHE_KEY, result,
                java.time.Duration.ofHours(4));
            log.info("Cached Claude situations in Redis (4h TTL)");
        } catch (Exception e) {
            log.info("Redis unavailable, Claude situations cached in-memory only");
        }
    }

    /**
     * Claude-Native Situation Detection.
     * Pre-filters countries with triggers, then uses Claude for semantic analysis.
     * Single API call for cost control.
     * Results are cached in memory for subsequent page loads.
     */
    public SituationDetectionResult detectSituations() {
        log.info("Starting Claude-Native situation detection");
        long start = System.currentTimeMillis();

        if (apiKey == null || apiKey.isBlank()) {
            return SituationDetectionResult.builder()
                    .status("ERROR")
                    .message("API key not configured")
                    .situations(List.of())
                    .build();
        }

        // Step 1: Collect signals and pre-filter countries with triggers
        List<CountrySignals> triggeredCountries = collectTriggeredCountries();

        if (triggeredCountries.isEmpty()) {
            // Don't cache empty results - keep existing cache so page isn't empty
            log.info("No triggered countries found - keeping existing cache");
            return SituationDetectionResult.builder()
                    .status("OK")
                    .message("No triggered countries detected")
                    .situations(List.of())
                    .analyzedCountries(0)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        // Step 2: Build batch prompt for Claude
        String prompt = buildSituationDetectionPrompt(triggeredCountries);

        try {
            Map<String, Object> request = Map.of(
                    "model", model,
                    "max_tokens", 3000,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            );

            String response = webClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(45))
                    .block();

            SituationDetectionResult result = parseSituationDetectionResponse(response);
            result.setAnalyzedCountries(triggeredCountries.size());
            result.setDurationMs(System.currentTimeMillis() - start);
            result.setModel(model);
            result.setGeneratedAt(LocalDateTime.now());

            // Only cache if we have situations - don't overwrite good cache with empty results
            if (result.getSituations() != null && !result.getSituations().isEmpty()) {
                cacheSituationResult(result);
                log.info("Situation detection completed: {} situations in {}ms (cached to Redis)",
                        result.getSituations().size(), result.getDurationMs());
            } else {
                log.info("Situation detection completed: 0 situations in {}ms (not cached, keeping existing)",
                        result.getDurationMs());
            }
            return result;

        } catch (Exception e) {
            log.error("Situation detection failed: {}", e.getMessage());
            return SituationDetectionResult.builder()
                    .status("ERROR")
                    .message("Analysis failed: " + e.getMessage())
                    .situations(List.of())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    /**
     * Collect signals for all countries and filter to those with triggers.
     * Uses CACHED batch data to avoid rate limiting.
     * Triggers: z-score > 2.0, currency collapse > 20%, flash updates, high risk score
     */
    @SuppressWarnings("unchecked")
    private List<CountrySignals> collectTriggeredCountries() {
        List<CountrySignals> triggered = new ArrayList<>();
        List<RiskScore> scores = cacheWarmupService != null
                ? (List<RiskScore>) cacheWarmupService.getFallback("allRiskScores") : null;
        if (scores == null || scores.isEmpty()) {
            scores = riskScoreService.getAllRiskScores();
        }
        if (scores == null || scores.isEmpty()) return triggered;

        // Use cached data only — never trigger expensive API calls from user actions
        @SuppressWarnings("unchecked")
        List<MediaSpike> allSpikes = cacheWarmupService != null
                ? (List<MediaSpike>) cacheWarmupService.getFallback("gdeltAllSpikes") : null;
        if (allSpikes == null) allSpikes = List.of();
        Map<String, MediaSpike> spikesByIso3 = new HashMap<>();
        for (MediaSpike spike : allSpikes) {
            if (spike.getIso3() != null) {
                spikesByIso3.put(spike.getIso3(), spike);
            }
        }

        @SuppressWarnings("unchecked")
        List<CurrencyData> allCurrency = cacheWarmupService != null
                ? (List<CurrencyData>) cacheWarmupService.getFallback("allCurrencyData")
                : currencyService.getAllCurrencyData();
        Map<String, CurrencyData> currencyByIso2 = new HashMap<>();
        if (allCurrency != null) {
            for (CurrencyData c : allCurrency) {
                if (c.getIso2() != null) {
                    currencyByIso2.put(c.getIso2(), c);
                }
            }
        }

        for (RiskScore score : scores) {
            String iso3 = score.getIso3();
            CountrySignals signals = new CountrySignals();
            signals.setIso3(iso3);
            signals.setCountryName(score.getCountryName());
            signals.setRiskScore(score.getScore());
            signals.setRiskLevel(score.getRiskLevel());

            boolean hasTrigger = false;

            // Check media spike from cached data (z-score > 2.0)
            MediaSpike spike = spikesByIso3.get(iso3);
            if (spike != null) {
                signals.setMediaZScore(spike.getZScore());
                signals.setMediaArticles(spike.getArticlesLast7Days());
                signals.setMediaSpikeLevel(spike.getSpikeLevel());
                if (spike.getTopHeadlines() != null && !spike.getTopHeadlines().isEmpty()) {
                    signals.setTopHeadlines(spike.getTopHeadlines().stream().limit(3).toList());
                }
                if (spike.getZScore() != null && spike.getZScore() > 2.0) {
                    hasTrigger = true;
                    signals.addTrigger("MEDIA_SPIKE", String.format("z=%.1f", spike.getZScore()));
                }
            }

            // Check currency collapse from cached data (> 20% devaluation in 30d)
            CurrencyData currency = currencyByIso2.get(score.getIso2());
            if (currency != null && currency.getChange30d() != null) {
                signals.setCurrencyChange30d(currency.getChange30d());
                if (currency.getChange30d() > 20) {
                    hasTrigger = true;
                    signals.addTrigger("CURRENCY_COLLAPSE", String.format("%.1f%%", currency.getChange30d()));
                }
            }

            // Check high risk score (>= 75) or deteriorating trend
            if (score.getScore() >= 75) {
                hasTrigger = true;
                signals.addTrigger("HIGH_RISK", String.format("Score %d", score.getScore()));
            }
            if (score.getScoreDelta() != null && score.getScoreDelta() >= 5) {
                hasTrigger = true;
                signals.addTrigger("DETERIORATING", String.format("+%d pts", score.getScoreDelta()));
            }

            // Add climate data from RiskScore (already computed)
            signals.setPrecipAnomaly(score.getPrecipitationAnomaly());

            // Add IPC if available from RiskScore
            signals.setIpcPhase(score.getIpcPhase());

            if (hasTrigger) {
                triggered.add(signals);
            }
        }

        // Sort by risk score descending, limit to top 10
        triggered.sort((a, b) -> b.getRiskScore() - a.getRiskScore());
        return triggered.stream().limit(10).toList();
    }

    private String buildSituationDetectionPrompt(List<CountrySignals> countries) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
You are a humanitarian early warning analyst. Detect ACTIVE SITUATIONS from the data below.

SITUATION TYPES (only use these):
- VIOLENCE_ESCALATION: Conflict intensity increasing, attacks, clashes, casualties
- DISPLACEMENT_SURGE: Population movement, refugees, IDPs, exodus
- FOOD_CRISIS: Famine risk, IPC deterioration, hunger
- ACCESS_CONSTRAINTS: Aid blocked, convoy attacks, humanitarian access denied
- CLIMATE_SHOCK: Flood, drought, cyclone impact
- HEALTH_EMERGENCY: Disease outbreak, epidemic

DETECTION RULES:
1. Only detect situations with CONCRETE EVIDENCE (headlines, reports, data spikes)
2. Do NOT infer situations from high risk scores alone
3. Severity: CRITICAL (immediate humanitarian impact), HIGH (escalating), ELEVATED (developing), WATCH (monitoring)
4. Each country can have 0, 1, or multiple situations

DATA FOR ANALYSIS:
""");

        for (CountrySignals c : countries) {
            sb.append("\n### ").append(c.getCountryName()).append(" (").append(c.getIso3()).append(")\n");
            sb.append("Risk: ").append(c.getRiskScore()).append(" (").append(c.getRiskLevel()).append(")\n");

            if (c.getTriggers() != null && !c.getTriggers().isEmpty()) {
                sb.append("Triggers: ").append(String.join(", ", c.getTriggers())).append("\n");
            }

            if (c.getMediaZScore() != null) {
                sb.append(String.format("Media: z=%.1f, %d articles, level=%s\n",
                        c.getMediaZScore(), c.getMediaArticles() != null ? c.getMediaArticles() : 0,
                        c.getMediaSpikeLevel() != null ? c.getMediaSpikeLevel() : "N/A"));
            }

            if (c.getTopHeadlines() != null && !c.getTopHeadlines().isEmpty()) {
                sb.append("Headlines: ");
                sb.append(c.getTopHeadlines().stream()
                        .map(h -> h.length() > 70 ? h.substring(0, 67) + "..." : h)
                        .collect(Collectors.joining(" | ")));
                sb.append("\n");
            }

            if (c.getCurrencyChange30d() != null && Math.abs(c.getCurrencyChange30d()) > 5) {
                sb.append(String.format("Currency: %.1f%% (30d)\n", c.getCurrencyChange30d()));
            }

            if (c.getPrecipAnomaly() != null && Math.abs(c.getPrecipAnomaly()) > 30) {
                sb.append(String.format("Climate: %.1f%% precip anomaly\n", c.getPrecipAnomaly()));
            }

            if (c.getIpcPhase() != null && c.getIpcPhase() >= 3) {
                sb.append(String.format("Food: IPC Phase %.0f\n", c.getIpcPhase()));
            }

            if (c.getRecentReports() != null && !c.getRecentReports().isEmpty()) {
                sb.append("Reports: ");
                sb.append(c.getRecentReports().stream()
                        .map(r -> r.length() > 60 ? r.substring(0, 57) + "..." : r)
                        .collect(Collectors.joining(" | ")));
                sb.append("\n");
            }
        }

        sb.append("""

OUTPUT JSON (array of situations, can be empty if no situations detected):
{
  "situations": [
    {
      "iso3": "<ISO3>",
      "countryName": "<name>",
      "type": "<SITUATION_TYPE>",
      "severity": "<CRITICAL|HIGH|ELEVATED|WATCH>",
      "summary": "<1 sentence: what is happening>",
      "evidence": ["<specific evidence 1>", "<evidence 2>"],
      "trajectory": "<WORSENING|STABLE|UNCLEAR>",
      "confidence": "<HIGH|MEDIUM|LOW>"
    }
  ],
  "globalContext": "<1 sentence on cross-border or regional patterns if any>"
}

Be precise. Only report situations with clear evidence. Empty array is valid if no situations detected.
""");

        return sb.toString();
    }

    private SituationDetectionResult parseSituationDetectionResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            String content = root.path("content").get(0).path("text").asText();

            // Extract JSON from response
            String json = content;
            if (content.contains("```json")) {
                json = content.substring(content.indexOf("```json") + 7);
                json = json.substring(0, json.indexOf("```"));
            } else if (content.contains("{")) {
                json = content.substring(content.indexOf("{"));
                json = json.substring(0, json.lastIndexOf("}") + 1);
            }

            JsonNode analysis = objectMapper.readTree(json);

            List<DetectedSituation> situations = new ArrayList<>();
            JsonNode situationsNode = analysis.path("situations");
            if (situationsNode.isArray()) {
                for (JsonNode s : situationsNode) {
                    List<String> evidence = new ArrayList<>();
                    s.path("evidence").forEach(e -> evidence.add(e.asText()));

                    // Validate severity — fallback if Claude returns empty/invalid
                    String severity = s.path("severity").asText("");
                    if (!Set.of("CRITICAL", "HIGH", "ELEVATED", "WATCH").contains(severity.toUpperCase())) {
                        // Derive from confidence or default to ELEVATED
                        String conf = s.path("confidence").asText("").toUpperCase();
                        severity = "HIGH".equals(conf) ? "HIGH" : "ELEVATED";
                    } else {
                        severity = severity.toUpperCase();
                    }

                    DetectedSituation situation = DetectedSituation.builder()
                            .iso3(s.path("iso3").asText())
                            .countryName(s.path("countryName").asText())
                            .type(s.path("type").asText())
                            .severity(severity)
                            .summary(s.path("summary").asText())
                            .evidence(evidence)
                            .trajectory(s.path("trajectory").asText())
                            .confidence(s.path("confidence").asText())
                            .build();
                    situations.add(situation);
                }
            }

            String globalContext = analysis.path("globalContext").asText(null);

            return SituationDetectionResult.builder()
                    .status("OK")
                    .situations(situations)
                    .globalContext(globalContext)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse situation detection response: {}", e.getMessage());
            return SituationDetectionResult.builder()
                    .status("ERROR")
                    .message("Failed to parse response: " + e.getMessage())
                    .situations(List.of())
                    .build();
        }
    }

    // ========================================
    // SITUATION DETECTION DTOs
    // ========================================

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SituationDetectionResult {
        private String status;
        private String message;
        private List<DetectedSituation> situations;
        private String globalContext;
        private int analyzedCountries;
        private long durationMs;
        private String model;
        private LocalDateTime generatedAt;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DetectedSituation {
        private String iso3;
        private String countryName;
        private String type;
        private String severity;
        private String summary;
        private List<String> evidence;
        private String trajectory;
        private String confidence;
    }

    @lombok.Data
    public static class CountrySignals {
        private String iso3;
        private String countryName;
        private int riskScore;
        private String riskLevel;
        private Double mediaZScore;
        private Integer mediaArticles;
        private String mediaSpikeLevel;
        private List<String> topHeadlines;
        private Double currencyChange30d;
        private Double precipAnomaly;
        private Double ipcPhase;
        private List<String> recentReports;
        private List<String> triggers = new ArrayList<>();

        public void addTrigger(String type, String detail) {
            triggers.add(type + ": " + detail);
        }
    }

    // ========================================
    // DEEP ANALYSIS RESULT DTO
    // ========================================

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DeepAnalysisResult {
        private String iso3;
        private String countryName;
        private int score;
        private String riskLevel;
        private Map<String, Integer> weights;
        private String weightReasoning;
        private String reasoning;
        private List<String> drivers;
        private String trajectory;
        private String trajectoryReason;
        private List<String> hotspots;
        private String confidenceLevel;
        private String confidenceReason;
        private LocalDateTime generatedAt;
        private String model;
        private long durationMs;
    }

    // ========== Q&A WITH CITATIONS ==========

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class QAResponse {
        private String question;
        private String answer;
        private List<QASource> sources;
        private LocalDateTime generatedAt;
        private String model;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class QASource {
        private int index;
        private String title;
        private String url;
        private String source;
        private String sourceType;
        private String country;
        private String timeAgo;
    }

    /**
     * Answer a question using cached news items as context.
     * Returns a response with inline [1], [2], [3] citations referencing source articles.
     */
    public QAResponse answerQuestion(String question) {
        if (apiKey == null || apiKey.isBlank()) {
            return QAResponse.builder()
                    .question(question)
                    .answer("AI analysis is not configured. Set anthropic.api.key to enable Q&A.")
                    .sources(List.of())
                    .generatedAt(LocalDateTime.now())
                    .build();
        }

        long start = System.currentTimeMillis();

        // 1. Gather cached news items + targeted Bing News search for the question
        List<NewsItem> allItems = gatherNewsItemsWithBingSearch(question);

        if (allItems.isEmpty()) {
            return QAResponse.builder()
                    .question(question)
                    .answer("No news data available yet. Please wait for the cache to warm up.")
                    .sources(List.of())
                    .generatedAt(LocalDateTime.now())
                    .build();
        }

        // 2. Search for relevant items using keyword matching
        List<NewsItem> relevant = searchRelevantItems(allItems, question);

        // Cap at 20 sources for prompt size
        if (relevant.size() > 20) {
            relevant = relevant.subList(0, 20);
        }

        // 3. Build numbered source list
        List<QASource> sources = new ArrayList<>();
        StringBuilder sourceContext = new StringBuilder();
        for (int i = 0; i < relevant.size(); i++) {
            NewsItem item = relevant.get(i);
            int idx = i + 1;
            sources.add(QASource.builder()
                    .index(idx)
                    .title(item.getTitle())
                    .url(item.getUrl())
                    .source(item.getSource())
                    .sourceType(item.getSourceType())
                    .country(item.getCountryName() != null ? item.getCountryName() : item.getCountry())
                    .timeAgo(item.getTimeAgo())
                    .build());
            sourceContext.append(String.format("[%d] %s — %s (%s) %s\n",
                    idx, item.getTitle(),
                    item.getSource() != null ? item.getSource() : item.getSourceType(),
                    item.getCountryName() != null ? item.getCountryName() : (item.getCountry() != null ? item.getCountry() : ""),
                    item.getTimeAgo() != null ? item.getTimeAgo() : ""));
        }

        // 4. Build prompt
        String prompt = buildQAPrompt(question, sourceContext.toString(), sources.size());

        // 5. Call Claude
        try {
            Map<String, Object> request = Map.of(
                    "model", model,
                    "max_tokens", 1024,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            );

            String response = webClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            JsonNode root = objectMapper.readTree(response);
            String answer = root.path("content").get(0).path("text").asText();

            long duration = System.currentTimeMillis() - start;
            log.info("Q&A answered in {}ms, {} sources, question: {}", duration, sources.size(),
                    question.length() > 80 ? question.substring(0, 80) + "..." : question);

            return QAResponse.builder()
                    .question(question)
                    .answer(answer)
                    .sources(sources)
                    .generatedAt(LocalDateTime.now())
                    .model(model)
                    .build();

        } catch (Exception e) {
            log.error("Q&A Claude call failed: {}", e.getMessage());
            return QAResponse.builder()
                    .question(question)
                    .answer("Unable to generate answer. Please try again in a moment.")
                    .sources(sources)
                    .generatedAt(LocalDateTime.now())
                    .build();
        }
    }

    private List<NewsItem> gatherNewsItems() {
        List<NewsItem> all = new ArrayList<>();
        try {
            // Get the default (all regions, all topics) news feed
            StoryService.NewsFeedData feed = storyService.getNewsFeed(null, null);
            if (feed != null) {
                if (feed.getReliefweb() != null) all.addAll(feed.getReliefweb());
                if (feed.getMedia() != null) all.addAll(feed.getMedia());
            }
        } catch (Exception e) {
            log.warn("Could not gather news items for Q&A: {}", e.getMessage());
        }
        return all;
    }

    /**
     * Gather news items from cached feed + targeted Bing News search based on the question.
     * This ensures Ask AI can find relevant sources even for countries/topics not in the cached feed.
     */
    private List<NewsItem> gatherNewsItemsWithBingSearch(String question) {
        List<NewsItem> all = gatherNewsItems();

        // Extract search query from question (remove stop words, keep meaningful terms)
        Set<String> stopWords = Set.of("what", "is", "the", "in", "a", "an", "and", "or", "of", "to",
                "for", "with", "on", "at", "by", "from", "about", "how", "why", "when", "where",
                "are", "was", "were", "been", "being", "have", "has", "had", "do", "does", "did",
                "will", "would", "could", "should", "can", "may", "might", "shall", "that", "this",
                "there", "their", "they", "it", "its", "but", "not", "no", "so", "if", "than",
                "happening", "going", "latest", "current", "situation", "tell", "me", "update");
        String[] words = question.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").split("\\s+");
        List<String> keywords = new ArrayList<>();
        for (String w : words) {
            if (w.length() >= 2 && !stopWords.contains(w)) {
                keywords.add(w);
            }
        }

        if (keywords.isEmpty()) {
            return all;
        }

        // Build Bing News search query from keywords
        String searchQuery = String.join(" ", keywords);
        try {
            List<Headline> bingHeadlines = fetchGoogleNewsHeadlines(searchQuery, 10);
            log.info("Bing News Q&A search for '{}': {} results", searchQuery, bingHeadlines.size());

            // Convert Headline -> NewsItem and add to pool
            for (Headline h : bingHeadlines) {
                all.add(NewsItem.builder()
                        .title(h.getTitle())
                        .url(h.getUrl())
                        .source(h.getSource())
                        .sourceType("BING_NEWS")
                        .timeAgo(h.getDate() != null ? h.getDate() : "Recent")
                        .build());
            }
        } catch (Exception e) {
            log.warn("Bing News search failed for Q&A query '{}': {}", searchQuery, e.getMessage());
        }

        return all;
    }

    private List<NewsItem> searchRelevantItems(List<NewsItem> items, String question) {
        // Tokenize question into keywords (lowercase, skip stop words)
        Set<String> stopWords = Set.of("what", "is", "the", "in", "a", "an", "and", "or", "of", "to",
                "for", "with", "on", "at", "by", "from", "about", "how", "why", "when", "where",
                "are", "was", "were", "been", "being", "have", "has", "had", "do", "does", "did",
                "will", "would", "could", "should", "can", "may", "might", "shall", "that", "this",
                "there", "their", "they", "it", "its", "but", "not", "no", "so", "if", "than",
                "happening", "going", "latest", "current", "situation", "tell", "me", "update");
        String[] words = question.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").split("\\s+");
        Set<String> keywords = new LinkedHashSet<>();
        for (String w : words) {
            if (w.length() >= 2 && !stopWords.contains(w)) {
                keywords.add(w);
            }
        }

        if (keywords.isEmpty()) {
            // No meaningful keywords — return most recent items
            return items.stream().limit(15).collect(Collectors.toList());
        }

        // Score each item by keyword match count
        List<Map.Entry<NewsItem, Integer>> scored = new ArrayList<>();
        for (NewsItem item : items) {
            String text = ((item.getTitle() != null ? item.getTitle() : "") + " " +
                    (item.getCountryName() != null ? item.getCountryName() : "") + " " +
                    (item.getCountry() != null ? item.getCountry() : "") + " " +
                    (item.getSource() != null ? item.getSource() : "") + " " +
                    (item.getTopics() != null ? String.join(" ", item.getTopics()) : ""))
                    .toLowerCase();

            int score = 0;
            for (String kw : keywords) {
                if (text.contains(kw)) score++;
            }
            if (score > 0) {
                scored.add(Map.entry(item, score));
            }
        }

        // Sort by score descending
        scored.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        // Return top matches (at least 5 if available)
        List<NewsItem> result = scored.stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // If few matches, add some recent items for broader context
        if (result.size() < 5) {
            for (NewsItem item : items) {
                if (!result.contains(item)) {
                    result.add(item);
                    if (result.size() >= 10) break;
                }
            }
        }

        return result;
    }

    private String buildQAPrompt(String question, String sourceContext, int sourceCount) {
        return String.format("""
                You are a humanitarian crisis analyst. Answer the user's question based ONLY on the news sources provided below.

                RULES:
                - Use inline citations like [1], [2], [3] referencing the source numbers
                - Every factual claim MUST have at least one citation
                - If the sources don't contain enough information to answer, say so honestly
                - Be concise but thorough — aim for 2-4 paragraphs
                - Use a neutral, analytical tone
                - Do NOT invent information not in the sources
                - Do NOT use markdown headers — write flowing prose with citations

                SOURCES (%d articles):
                %s

                QUESTION: %s

                Answer with inline citations:""",
                sourceCount, sourceContext, question);
    }
}
