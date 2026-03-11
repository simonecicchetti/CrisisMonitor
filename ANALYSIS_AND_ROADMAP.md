# Crisis Monitor: Analisi Completa e Roadmap per Forecasting

## 1. STATO ATTUALE DEL SISTEMA

### 1.1 Data Sources Attuali

| Fonte | Tipo Dato | Freschezza | Copertura | Limite |
|-------|-----------|------------|-----------|--------|
| **HungerMap (WFP)** | Food Security, IPC | Daily | 80+ paesi | Solo food security |
| **UNHCR Population** | Rifugiati, IDP, Asilo | Annual (2023) | Globale | Dati annuali, ritardo 1+ anno |
| **IOM DTM** | IDP tracking | Monthly | 54 paesi | Solo IDP, no refugees |
| **World Bank** | Economia (inflazione, GDP) | Annual | 200+ paesi | Ritardo 1-2 anni |
| **PDC DisasterAWARE** | Hazard naturali | Real-time | Globale | Solo eventi attivi |
| **Darien Migration** | Flussi migratori | Daily | 1 corridoio | Solo Darien Gap |

### 1.2 Gap Critici Identificati

```
┌─────────────────────────────────────────────────────────────────┐
│                    MATRICE DEI GAP                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  TEMPORALE                     TEMATICO                          │
│  ──────────                    ────────                          │
│  • UNHCR: dati 2023           • Conflitti: ASSENTE               │
│  • World Bank: 1-2 anni       • Notizie: ASSENTE                 │
│  • No near-real-time          • Social sentiment: ASSENTE        │
│  • No previsioni              • Early warning: ASSENTE           │
│                                                                  │
│  GEOGRAFICO                    ANALITICO                         │
│  ──────────                    ─────────                         │
│  • Darien: solo 1 corridoio   • No correlazioni                  │
│  • DTM: 54 paesi solo         • No trend analysis                │
│  • No Med/Balkan routes       • No forecasting                   │
│  • No internal EU             • No risk scoring                  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. API PUBBLICHE DISPONIBILI PER MIGLIORAMENTO

### 2.1 GDELT Project (PRIORITÀ ALTA)

**URL:** https://api.gdeltproject.org/

GDELT monitora news globali in tempo reale e può colmare i gap di:
- **Conflitti** (eventi violenza, proteste, tensioni)
- **Sentiment** (tono mediatico per paese)
- **Early Warning** (picchi di eventi negativi)

#### Endpoints Chiave:

```
1. GEO 2.0 API (Event Monitoring)
   GET https://api.gdeltproject.org/api/v2/geo/geo?query=...

   Parametri:
   - query: country:syria OR country:sudan
   - mode: artlist (articoli) | pointdata (eventi geo)
   - timespan: 24h | 7d | 30d

2. DOC 2.0 API (Document Search)
   GET https://api.gdeltproject.org/api/v2/doc/doc?query=...

   Parametri:
   - query: "food crisis" OR "displacement" OR "conflict"
   - mode: artlist | timelinevol | tonechart
   - sourcelang: eng | ara | fra

3. TV API (Broadcast Monitoring)
   GET https://api.gdeltproject.org/api/v2/tv/tv?query=...

   Monitora menzioni TV globali

4. CAMEO Codes (Event Classification)
   - 14*: Proteste
   - 17*: Coercizione
   - 18*: Assalto
   - 19*: Combattimento
   - 20*: Violenza di massa
```

#### Implementazione Suggerita:

```java
@Service
public class GDELTService {

    // Monitora eventi conflitto per paese
    public List<ConflictEvent> getConflictEvents(String iso3, int days);

    // Trend di copertura mediatica (early warning)
    public Map<String, Double> getMediaAttention(String iso3, int days);

    // Sentiment/Tono medio per paese
    public Double getAverageTone(String iso3, int days);

    // Picchi di eventi negativi (alert trigger)
    public List<AlertSpike> detectSpikes(String iso3, int days);
}
```

### 2.2 ACLED (Armed Conflict Location & Event Data)

**URL:** https://acleddata.com/

**Tipo:** Conflitti armati, violenza politica, proteste

**API:** https://api.acleddata.com/acled/read

```
Parametri:
- iso: codice paese (numerico)
- event_date: range date
- event_type: battles, violence against civilians, protests, riots
- limit: max 5000 records

Esempio:
GET https://api.acleddata.com/acled/read?key=KEY&email=EMAIL
    &iso=729&event_date=2024-01-01|2024-12-31
```

**Nota:** Richiede registrazione gratuita per API key

### 2.3 ReliefWeb API

**URL:** https://api.reliefweb.int/v1

**Tipo:** Report umanitari, situational reports, news

```
Endpoints:
- /reports: Report completi
- /disasters: Eventi disastro
- /countries: Info paesi
- /sources: Fonti (UN agencies, NGOs)

Esempio:
GET https://api.reliefweb.int/v1/reports?appname=crisis-monitor
    &filter[field]=country.iso3&filter[value]=SYR
    &filter[field]=date.created&filter[value][from]=2024-01-01
    &limit=100
```

### 2.4 FEWS NET (Famine Early Warning)

**URL:** https://fews.net/

**Dati:** IPC projections, food security outlook

```
Data Access:
- IPC Analysis Archive
- Food Security Outlook reports
- Price monitoring data
- Seasonal calendar data

API: HDX mirror disponibile
```

### 2.5 INFORM Risk Index

**URL:** https://drmkc.jrc.ec.europa.eu/inform-index

**Tipo:** Risk scoring multidimensionale

```
Dimensioni:
- Hazard & Exposure (natural, human-made)
- Vulnerability (socio-economic, vulnerable groups)
- Lack of Coping Capacity (institutional, infrastructure)

Score: 0-10 per ogni dimensione
Update: Annual + mid-year
```

### 2.6 Climate Data APIs

#### 2.6.1 Open-Meteo (Gratuito, no key)
```
GET https://api.open-meteo.com/v1/forecast?
    latitude=15.5&longitude=32.5
    &daily=precipitation_sum,temperature_2m_max
    &timezone=Africa/Khartoum
```

#### 2.6.2 NASA POWER (Gratuito)
```
GET https://power.larc.nasa.gov/api/temporal/daily/point?
    parameters=PRECTOTCORR,T2M,QV2M
    &community=AG&longitude=32.5&latitude=15.5
    &start=20240101&end=20241231&format=JSON
```

### 2.7 Frontex Migratory Routes (EU)

**URL:** https://frontex.europa.eu/what-we-do/monitoring-and-risk-analysis/migratory-map/

**Dati:** Detections per route (Central Med, Eastern Med, Western Balkans, etc.)

**Accesso:** Monthly reports, no direct API - scraping needed

### 2.8 IPC Global Platform

**URL:** https://www.ipcinfo.org/

**API:** https://www.ipcinfo.org/fileadmin/user_upload/ipcinfo/docs/IPC_API_documentation.pdf

```
Dati:
- IPC Phase Classification ufficiale
- Projected food insecurity
- Area-level analysis
```

---

## 3. ARCHITETTURA PROPOSTA PER FORECASTING

### 3.1 Data Lake Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     DATA INGESTION LAYER                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  REAL-TIME (< 1h)           DAILY               PERIODIC         │
│  ─────────────────          ─────               ────────         │
│  • GDELT Events             • HungerMap         • UNHCR (Annual) │
│  • PDC Hazards              • ACLED             • World Bank     │
│  • News Feeds               • Darien            • INFORM Index   │
│  • Social Media             • ReliefWeb         • IPC Official   │
│                             • Weather           • DTM            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    DATA PROCESSING LAYER                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  NORMALIZATION              CORRELATION          AGGREGATION     │
│  ─────────────              ───────────          ───────────     │
│  • ISO3 standard            • Cross-source       • Country-level │
│  • Date alignment             matching           • Region-level  │
│  • Unit conversion          • Conflict-Food      • Global        │
│  • Quality scoring            linkage            • Time series   │
│                             • Climate-Food                       │
│                               linkage                            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    ANALYTICS LAYER                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  RISK SCORING               TREND ANALYSIS       FORECASTING     │
│  ────────────               ──────────────       ───────────     │
│  • Composite index          • Moving averages    • ML models     │
│  • Multi-dimensional        • Anomaly detect     • Time series   │
│  • Weighted factors         • Seasonality        • Scenario      │
│  • Confidence level         • Acceleration       • Probability   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    EARLY WARNING SYSTEM                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  TRIGGERS                   ALERTS               FORECASTS       │
│  ────────                   ──────               ─────────       │
│  • Threshold breach         • Push notifications • 30-60-90 day  │
│  • Trend acceleration       • Email digest       • Confidence %  │
│  • Anomaly detection        • Dashboard badges   • Risk factors  │
│  • Correlation spike        • API webhooks       • Scenarios     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 Composite Risk Score Model

```
CRISIS RISK INDEX (CRI) = Σ(Wi × Di × Ci)

Dove:
  Wi = Weight (importanza relativa)
  Di = Data point normalizzato (0-100)
  Ci = Confidence/freshness factor (0-1)

DIMENSIONI:

┌──────────────────────────────────────────────────────────────────┐
│ DIMENSIONE           │ PESO │ INDICATORI                        │
├──────────────────────┼──────┼────────────────────────────────────┤
│ Food Security        │ 25%  │ IPC Phase, FCS, rCSI              │
│ Conflict Intensity   │ 20%  │ ACLED events, GDELT, fatalities   │
│ Displacement         │ 20%  │ UNHCR, DTM, new displacement      │
│ Economic Stress      │ 15%  │ Inflation, GDP decline, poverty   │
│ Climate/Environment  │ 10%  │ NDVI, drought, floods             │
│ Governance/Coping    │ 10%  │ INFORM index, institutional       │
└──────────────────────┴──────┴────────────────────────────────────┘

FORMULA APPLICATA:

CRI = (0.25 × FoodSecurityScore × Cf) +
      (0.20 × ConflictScore × Cc) +
      (0.20 × DisplacementScore × Cd) +
      (0.15 × EconomicScore × Ce) +
      (0.10 × ClimateScore × Ccl) +
      (0.10 × GovernanceScore × Cg)

OUTPUT: 0-100 score con classificazione:
  • 80-100: CRITICAL (immediate action needed)
  • 60-79:  HIGH (escalating crisis)
  • 40-59:  ELEVATED (monitoring required)
  • 20-39:  MODERATE (watch list)
  • 0-19:   LOW (stable)
```

### 3.3 Forecasting Models

#### 3.3.1 Time Series Forecasting

```python
# Approccio: ARIMA + External Regressors

from statsmodels.tsa.arima.model import ARIMA
from sklearn.ensemble import RandomForestRegressor

def forecast_food_insecurity(country_iso3, horizon_days=90):
    """
    Forecast IPC phase progression using:
    1. Historical IPC trends
    2. Conflict events (ACLED/GDELT) as regressor
    3. Climate anomalies (NDVI) as regressor
    4. Economic shocks (inflation) as regressor
    """

    # Load historical data
    ipc_history = get_ipc_timeseries(country_iso3)
    conflict_events = get_conflict_timeseries(country_iso3)
    ndvi_anomaly = get_climate_timeseries(country_iso3)
    inflation = get_economic_timeseries(country_iso3)

    # Combine features
    X = pd.concat([conflict_events, ndvi_anomaly, inflation], axis=1)
    y = ipc_history['phase_3_5_percent']

    # Train model
    model = ARIMA(y, exog=X, order=(1,1,1))
    fitted = model.fit()

    # Forecast
    future_X = project_regressors(X, horizon_days)
    forecast = fitted.forecast(steps=horizon_days, exog=future_X)

    return {
        'forecast': forecast,
        'confidence_interval': fitted.get_forecast(horizon_days).conf_int(),
        'model_accuracy': fitted.aic
    }
```

#### 3.3.2 Event-Based Triggers

```java
public class EarlyWarningEngine {

    // Trigger types
    public enum TriggerType {
        CONFLICT_SPIKE,      // >50% increase in events
        DISPLACEMENT_SURGE,  // >10K new IDPs in week
        FOOD_CRISIS,         // IPC phase deterioration
        ECONOMIC_SHOCK,      // >25% inflation spike
        CLIMATE_STRESS,      // NDVI < 0.7
        MEDIA_ATTENTION      // GDELT volume spike
    }

    public List<Alert> checkTriggers(String iso3) {
        List<Alert> alerts = new ArrayList<>();

        // Check each trigger
        if (detectConflictSpike(iso3)) {
            alerts.add(new Alert(TriggerType.CONFLICT_SPIKE,
                "Conflict events up 50%+ in past 7 days"));
        }

        if (detectDisplacementSurge(iso3)) {
            alerts.add(new Alert(TriggerType.DISPLACEMENT_SURGE,
                "New displacement exceeds 10,000 in past week"));
        }

        // ... other triggers

        return alerts;
    }

    // Correlation-based forecast
    public CrisisRisk forecastRisk(String iso3, int daysAhead) {
        // Get current indicators
        double conflictTrend = getConflictTrend(iso3);
        double foodTrend = getFoodSecurityTrend(iso3);
        double climateTrend = getClimateTrend(iso3);

        // Apply learned correlations
        // Historical: conflict spike → food crisis in 30-60 days
        // Historical: drought (NDVI<0.7) → displacement in 60-90 days

        double riskScore = calculateCompositeRisk(
            conflictTrend * 1.2,  // Leading indicator weight
            foodTrend,
            climateTrend * 0.8
        );

        return new CrisisRisk(iso3, daysAhead, riskScore, confidence);
    }
}
```

---

## 4. IMPLEMENTAZIONE PRIORITIZZATA

### Fase 1: GDELT Integration (1-2 settimane)

```
Obiettivo: Near-real-time conflict & media monitoring

Tasks:
□ Creare GDELTService.java
□ Implementare endpoint per eventi conflitto
□ Implementare media attention tracker
□ Aggiungere sentiment/tone analysis
□ Creare ConflictEvent model
□ Integrare in Dashboard
□ Cache: 15 minuti per queries GDELT
```

### Fase 2: ACLED Integration (1 settimana)

```
Obiettivo: Validated conflict event data

Tasks:
□ Registrazione API key (gratuita)
□ Creare ACLEDService.java
□ Mapping event types to categories
□ Correlazione con GDELT per validation
□ Dashboard: Conflict intensity widget
```

### Fase 3: Composite Risk Score (2 settimane)

```
Obiettivo: Unified crisis scoring

Tasks:
□ Creare RiskScoringService.java
□ Implementare formula CRI
□ Normalizzazione indicatori (0-100)
□ Weight calibration based on historical crises
□ Dashboard: Country risk ranking
□ API: /api/risk/score/{iso3}
□ API: /api/risk/ranking
```

### Fase 4: Time Series Analysis (2-3 settimane)

```
Obiettivo: Trend detection & basic forecasting

Tasks:
□ Implementare historical data storage
□ Moving average calculations
□ Anomaly detection (z-score based)
□ Trend acceleration indicators
□ Dashboard: Trend sparklines
□ Dashboard: Anomaly alerts
```

### Fase 5: ML Forecasting (3-4 settimane)

```
Obiettivo: Predictive crisis model

Tasks:
□ Python microservice per ML (FastAPI)
□ Training pipeline con dati storici
□ ARIMA + RF ensemble model
□ Cross-validation su crisi passate
□ Confidence intervals
□ API: /api/forecast/{iso3}?days=90
□ Dashboard: Forecast widget
```

---

## 5. QUICK WINS IMMEDIATI

### 5.1 Miglioramenti con API Esistenti

```java
// 1. UNHCR: Usa dati 2024 quando disponibili
// Cambia year=2023 → year=2024 in UNHCRService

// 2. HungerMap: Aggiungi FCS predictions
// Endpoint: /v1/foodsecurity/predictions

// 3. PDC: Aggiungi historical hazards per trend
// Endpoint: /msf/rest/services/global/pdc_archive/...

// 4. World Bank: Indicatori più frequenti
// FP.CPI.TOTL.ZG.M = Monthly inflation (se disponibile)
```

### 5.2 GDELT Simple Integration (No ML)

```java
@Service
public class GDELTSimpleService {

    private static final String GDELT_GEO_API =
        "https://api.gdeltproject.org/api/v2/geo/geo";

    /**
     * Get conflict event count for country in last N days
     */
    public int getConflictEventCount(String countryName, int days) {
        String query = String.format(
            "country:%s (protest OR conflict OR violence OR military)",
            countryName.toLowerCase()
        );

        String url = UriComponentsBuilder.fromHttpUrl(GDELT_GEO_API)
            .queryParam("query", query)
            .queryParam("mode", "artlist")
            .queryParam("timespan", days + "d")
            .queryParam("format", "json")
            .build().toUriString();

        // Parse response and count articles
        JsonNode response = webClient.get().uri(url)...
        return response.get("articles").size();
    }

    /**
     * Get average tone (sentiment) for country
     * Negative tone = potential crisis indicator
     */
    public double getAverageTone(String countryName, int days) {
        // GDELT returns tone from -100 to +100
        // Negative = negative coverage
        // Use as early warning signal
    }
}
```

---

## 6. METRICHE DI SUCCESSO

### 6.1 Forecasting Accuracy

```
Target Metrics:
- Precision@30days: >70% (crisis correctly predicted)
- Recall@30days: >60% (crises not missed)
- Lead Time: >14 days average warning
- False Positive Rate: <30%
```

### 6.2 Data Quality

```
Coverage:
- Countries monitored: 100+ (currently 80)
- Data freshness: <24h for 80% of indicators
- Source diversity: 10+ independent sources
- Update frequency: Real-time for events, daily for metrics
```

### 6.3 System Performance

```
Response Times:
- Dashboard load: <3s
- API endpoints: <500ms
- Forecast generation: <5s
- Alert propagation: <1min from trigger
```

---

## 7. ARCHITETTURA TECNICA FINALE

```
┌─────────────────────────────────────────────────────────────────┐
│                    CRISIS MONITOR v2.0                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │ HungerMap   │  │   UNHCR     │  │  World Bank │              │
│  │  (Food)     │  │ (Displace)  │  │  (Economy)  │              │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘              │
│         │                │                │                      │
│  ┌──────┴──────┐  ┌──────┴──────┐  ┌──────┴──────┐              │
│  │   GDELT     │  │   ACLED     │  │   INFORM    │              │
│  │  (Events)   │  │ (Conflict)  │  │   (Risk)    │              │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘              │
│         │                │                │                      │
│         └────────────────┼────────────────┘                      │
│                          │                                       │
│                          ▼                                       │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              DATA INTEGRATION LAYER                        │  │
│  │  • Normalization  • Correlation  • Quality Scoring         │  │
│  └───────────────────────────────────────────────────────────┘  │
│                          │                                       │
│                          ▼                                       │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              ANALYTICS ENGINE                              │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │  │
│  │  │ Risk Score  │  │   Trends    │  │  Forecast   │        │  │
│  │  │   (CRI)     │  │  Analysis   │  │    (ML)     │        │  │
│  │  └─────────────┘  └─────────────┘  └─────────────┘        │  │
│  └───────────────────────────────────────────────────────────┘  │
│                          │                                       │
│                          ▼                                       │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              EARLY WARNING SYSTEM                          │  │
│  │  • Threshold Triggers  • Alert Generation  • Notifications │  │
│  └───────────────────────────────────────────────────────────┘  │
│                          │                                       │
│                          ▼                                       │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              DASHBOARD & API                               │  │
│  │  • Real-time Map  • Rankings  • Forecasts  • REST API     │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 8. CONCLUSIONI E RACCOMANDAZIONI

### Priorità Immediata (Settimana 1):
1. **Integrare GDELT** - Near-real-time event monitoring
2. **Aggiungere conflict dimension** - Gap critico attuale

### Priorità Alta (Mese 1):
3. **ACLED integration** - Validated conflict data
4. **Composite Risk Score** - Unified crisis metric
5. **Trend analysis** - Anomaly detection

### Priorità Media (Mese 2-3):
6. **ML Forecasting** - Predictive capabilities
7. **Additional routes** - Mediterranean, Balkans migration
8. **INFORM Index** - Governance/coping capacity

### Considerazioni Finali:

Il sistema attuale è solido per **monitoring** ma manca completamente di:
- **Conflict data** (GDELT/ACLED)
- **Trend analysis** (no historical)
- **Forecasting** (no predictions)
- **Early warning** (no triggers)

Con l'aggiunta di GDELT + ACLED + Risk Scoring si può costruire un sistema
di early warning credibile con **14-30 giorni di lead time** per la maggior
parte delle crisi umanitarie, con una **confidenza del 70%+** basata su
correlazioni storiche documentate tra conflict escalation → food insecurity
→ displacement.
