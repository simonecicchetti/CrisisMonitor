/**
 * Crisis Monitor - Risk Score Map
 * Displays OUR risk scores, not WFP HungerMap data.
 */

const MapConfig = {
  center: [15, 20],
  zoom: 2.5,
  minZoom: 2,
  maxZoom: 8,
  tileUrl: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
  tileAttribution: '&copy; OpenStreetMap &copy; CARTO'
};

// Our risk level colors
const RiskColors = {
  'CRITICAL': '#ff453a',
  'ALERT':    '#ff9f0a',
  'WARNING':  '#ffd60a',
  'WATCH':    '#64d2ff',
  'STABLE':   '#30d158'
};

// Country centroid coordinates
const CountryCoords = {
  AFG:[33.93,67.71],AGO:[-11.2,17.87],BDI:[-3.37,29.92],BFA:[12.24,-1.56],BGD:[23.68,90.36],
  CAF:[6.61,20.94],CMR:[7.37,12.35],COD:[-4.04,21.76],COG:[-0.23,15.83],COL:[4.57,-74.3],
  CUB:[21.52,-77.78],ECU:[-1.83,-78.18],ETH:[9.15,40.49],GTM:[15.78,-90.23],HND:[15.2,-86.24],
  HTI:[19.07,-72.29],IRN:[32.43,53.69],IRQ:[33.22,43.68],ISR:[31.05,34.85],KEN:[-0.02,37.91],
  LBN:[33.85,35.86],LBY:[26.34,17.23],MEX:[23.63,-102.55],MLI:[17.57,-4.0],MMR:[19.76,96.08],
  MOZ:[-18.67,35.53],NER:[17.61,8.08],NGA:[9.08,8.68],NIC:[12.87,-85.21],PAK:[30.38,69.35],
  PAN:[8.54,-80.78],PER:[-9.19,-75.02],PSE:[31.95,35.23],RWA:[-1.94,29.87],SDN:[12.86,30.22],
  SLV:[13.79,-88.9],SOM:[5.15,46.2],SSD:[6.88,31.31],SYR:[34.8,38.99],TCD:[15.45,18.73],
  UGA:[1.37,32.29],UKR:[48.38,31.17],VEN:[6.42,-66.59],YEM:[15.55,48.52],
};

const HazardEmoji = {
  'FLOOD':'🌊','EARTHQUAKE':'🌍','CYCLONE':'🌀','DROUGHT':'☀️',
  'VOLCANO':'🌋','WILDFIRE':'🔥','STORM':'⛈️','TSUNAMI':'🌊','DEFAULT':'⚠️'
};

let map, riskMarkers, hazardMarkers, mapInitialized = false;

function initMap() {
  if (mapInitialized) { if (map) map.invalidateSize(); return; }
  const el = document.getElementById('map');
  if (!el) return;
  const rect = el.getBoundingClientRect();
  if (rect.width === 0 || rect.height === 0) return;

  map = L.map('map', {
    center: MapConfig.center, zoom: MapConfig.zoom,
    minZoom: MapConfig.minZoom, maxZoom: MapConfig.maxZoom,
    worldCopyJump: true, zoomControl: true, attributionControl: false
  });
  L.tileLayer(MapConfig.tileUrl, { subdomains: 'abcd', maxZoom: 19 }).addTo(map);

  riskMarkers = L.layerGroup().addTo(map);
  hazardMarkers = L.layerGroup().addTo(map);

  loadRiskScores();
  loadHazards();
  mapInitialized = true;

  setInterval(() => { loadRiskScores(); loadHazards(); }, 5 * 60 * 1000);
}

// ============================================
// RISK SCORE MARKERS (OUR DATA)
// ============================================
async function loadRiskScores() {
  try {
    const resp = await fetch('/api/risk/scores');
    const result = await resp.json();
    const scores = result.data || result.scores || result;
    if (!Array.isArray(scores)) return;

    riskMarkers.clearLayers();

    scores.forEach((s, i) => {
      const coords = CountryCoords[s.iso3];
      if (!coords) return;

      const color = RiskColors[s.riskLevel] || 'rgba(255,255,255,0.3)';
      const radius = scoreToRadius(s.score);
      const pulseClass = s.riskLevel === 'CRITICAL' ? 'pulse-marker' : '';

      const marker = L.circleMarker(coords, {
        radius: radius,
        fillColor: color,
        color: 'rgba(255,255,255,0.4)',
        weight: 1.5,
        opacity: 1,
        fillOpacity: 0.75,
        className: `country-marker ${pulseClass}`
      });

      marker.setStyle({ opacity: 0, fillOpacity: 0 });
      setTimeout(() => marker.setStyle({ opacity: 1, fillOpacity: 0.75 }), i * 15);

      marker.bindPopup(createRiskPopup(s), { className: 'modern-popup', maxWidth: 320 });

      marker.on('mouseover', function() {
        this.setStyle({ weight: 3, fillOpacity: 0.9 });
        this.setRadius(radius * 1.2);
      });
      marker.on('mouseout', function() {
        this.setStyle({ weight: 1.5, fillOpacity: 0.75 });
        this.setRadius(radius);
      });

      riskMarkers.addLayer(marker);
    });
  } catch (e) {
    console.error('Error loading risk scores for map:', e);
  }
}

function scoreToRadius(score) {
  if (score >= 70) return 18;
  if (score >= 55) return 14;
  if (score >= 40) return 11;
  if (score >= 25) return 8;
  return 6;
}

function createRiskPopup(s) {
  const color = RiskColors[s.riskLevel] || '#aaa';
  const trend = s.trendIcon ? `<span style="margin-left:4px;">${s.trendIcon} ${s.scoreDelta > 0 ? '+' : ''}${s.scoreDelta || ''}</span>` : '';
  const drivers = (s.drivers || []).map(d => `<span class="popup-tag">${d}</span>`).join('');

  // Component bars
  const components = [
    { name: 'Food', value: s.foodSecurityScore, color: '#ff9f0a', reason: s.foodReason },
    { name: 'Conflict', value: s.conflictScore, color: '#ff453a', reason: s.conflictReason },
    { name: 'Climate', value: s.climateScore, color: '#64d2ff', reason: s.climateReason },
    { name: 'Economic', value: s.economicScore, color: '#ffd60a', reason: s.economicReason },
  ];
  const bars = components.map(c =>
    `<div style="display:flex;align-items:center;gap:6px;margin:3px 0;">
      <span style="width:55px;font-size:0.7rem;color:rgba(255,255,255,0.5);">${c.name}</span>
      <div style="flex:1;height:4px;background:rgba(255,255,255,0.1);border-radius:2px;overflow:hidden;">
        <div style="width:${c.value}%;height:100%;background:${c.color};border-radius:2px;"></div>
      </div>
      <span style="width:24px;font-size:0.7rem;text-align:right;color:rgba(255,255,255,0.6);">${c.value}</span>
    </div>
    ${c.reason ? `<div style="font-size:0.58rem;color:rgba(255,255,255,0.35);margin:-1px 0 3px 61px;line-height:1.2;">${c.reason}</div>` : ''}`
  ).join('');

  return `
    <div class="popup-content">
      <div style="display:flex;justify-content:space-between;align-items:center;">
        <div class="popup-title">${s.countryName}</div>
        <span style="font-size:1.3rem;font-weight:700;color:${color};">${s.score}</span>
      </div>
      <div style="display:flex;align-items:center;gap:6px;margin:4px 0 8px;">
        <span style="padding:2px 8px;border-radius:4px;font-size:0.7rem;font-weight:600;background:${color}20;color:${color};">${s.riskLevel}</span>
        ${trend}
      </div>
      <div class="popup-divider"></div>
      ${bars}
      ${drivers ? `<div style="display:flex;gap:4px;margin-top:8px;flex-wrap:wrap;">${drivers}</div>` : ''}
      ${s.summary ? `<div style="font-size:0.65rem;color:rgba(255,255,255,0.45);margin-top:8px;font-style:italic;">${s.summary}</div>` : ''}
      ${s.scoreSource === 'qwen' ? `<div style="font-size:0.55rem;color:rgba(100,210,255,0.5);margin-top:4px;">AI-assessed · ${s.qwenGeneratedAt || ''}</div>` : ''}
    </div>
  `;
}

// ============================================
// HAZARD MARKERS (keep as-is)
// ============================================
async function loadHazards() {
  try {
    const resp = await fetch('/api/hazards');
    const hazards = await resp.json();
    hazardMarkers.clearLayers();

    hazards.forEach(h => {
      if (!h.latitude || !h.longitude) return;
      const emoji = HazardEmoji[h.type] || HazardEmoji['DEFAULT'];
      const marker = L.marker([h.latitude, h.longitude], {
        icon: L.divIcon({
          className: 'hazard-marker',
          html: `<div class="hazard-marker-inner ${(h.severity||'').toLowerCase()}"><span class="hazard-emoji">${emoji}</span></div>`,
          iconSize: [36, 36], iconAnchor: [18, 18]
        })
      });
      marker.bindPopup(createHazardPopup(h), { className: 'modern-popup', maxWidth: 280 });
      hazardMarkers.addLayer(marker);
    });
  } catch (e) {
    console.error('Error loading hazards:', e);
  }
}

function createHazardPopup(h) {
  const emoji = HazardEmoji[h.type] || HazardEmoji['DEFAULT'];
  return `
    <div class="popup-content">
      <div class="popup-title">${emoji} ${h.name}</div>
      <div class="popup-divider"></div>
      <div class="popup-tags">
        <span class="popup-tag">${h.type}</span>
        <span class="popup-tag ${(h.severity||'').toLowerCase()}">${h.severity}</span>
      </div>
      ${h.description ? `<p class="popup-description">${h.description}</p>` : ''}
    </div>
  `;
}

function formatNumber(n) {
  if (n >= 1e6) return (n/1e6).toFixed(1)+'M';
  if (n >= 1e3) return (n/1e3).toFixed(0)+'K';
  return n.toString();
}

// Styles
const mapStyles = document.createElement('style');
mapStyles.textContent = `
  .country-marker { transition: all 0.2s cubic-bezier(0.34,1.56,0.64,1); }
  .pulse-marker { animation: marker-pulse 2s ease-in-out infinite; }
  @keyframes marker-pulse { 0%,100%{opacity:0.75;} 50%{opacity:1;} }
  .hazard-marker { filter: drop-shadow(0 2px 8px rgba(0,0,0,0.4)); }
  .hazard-marker-inner { width:36px;height:36px;display:flex;align-items:center;justify-content:center;background:rgba(28,28,36,0.9);backdrop-filter:blur(10px);border-radius:50%;border:2px solid rgba(255,255,255,0.2);animation:hazard-pulse 2s ease-in-out infinite; }
  .hazard-marker-inner.warning { border-color:#ff453a;box-shadow:0 0 20px rgba(255,69,58,0.4); }
  .hazard-marker-inner.watch { border-color:#ff9f0a;box-shadow:0 0 20px rgba(255,159,10,0.4); }
  .hazard-emoji { font-size:18px; }
  @keyframes hazard-pulse { 0%,100%{transform:scale(1);} 50%{transform:scale(1.1);} }
  .modern-popup .leaflet-popup-content-wrapper { background:rgba(28,28,36,0.95);backdrop-filter:blur(20px);-webkit-backdrop-filter:blur(20px);border-radius:16px;border:1px solid rgba(255,255,255,0.1);box-shadow:0 20px 60px rgba(0,0,0,0.5);padding:0; }
  .modern-popup .leaflet-popup-content { margin:0;color:#fff; }
  .modern-popup .leaflet-popup-tip { background:rgba(28,28,36,0.95); }
  .popup-content { padding:16px; }
  .popup-title { font-size:1.1rem;font-weight:600;margin-bottom:4px; }
  .popup-divider { height:1px;background:rgba(255,255,255,0.1);margin:8px 0; }
  .popup-tags { display:flex;gap:6px;margin-bottom:8px;flex-wrap:wrap; }
  .popup-tag { padding:3px 8px;background:rgba(255,255,255,0.1);border-radius:6px;font-size:0.7rem;font-weight:500;text-transform:uppercase; }
  .popup-tag.warning { background:rgba(255,69,58,0.2);color:#ff453a; }
  .popup-tag.watch { background:rgba(255,159,10,0.2);color:#ff9f0a; }
  .popup-description { font-size:0.85rem;color:rgba(255,255,255,0.7);line-height:1.5; }
`;
document.head.appendChild(mapStyles);

document.addEventListener('DOMContentLoaded', initMap);

window.CrisisMap = {
  init: initMap,
  loadCountries: loadRiskScores,
  loadHazards,
  map: () => map,
  invalidateSize: () => { if (map) map.invalidateSize(); }
};
