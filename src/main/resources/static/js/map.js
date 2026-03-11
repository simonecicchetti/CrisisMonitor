/**
 * Crisis Monitor - Interactive Map
 * Apple Style 2026
 */

// ============================================
// MAP CONFIGURATION
// ============================================
const MapConfig = {
  center: [20, 0],
  zoom: 2,
  minZoom: 2,
  maxZoom: 8,
  tileUrl: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
  tileAttribution: '&copy; OpenStreetMap &copy; CARTO'
};

// Apple-inspired color palette
const SeverityColors = {
  'CRITICAL': '#ff453a',
  'HIGH': '#ff9f0a',
  'MEDIUM': '#64d2ff',
  'LOW': '#30d158',
  'NO_DATA': 'rgba(255, 255, 255, 0.3)'
};

const HazardEmoji = {
  'FLOOD': '🌊',
  'EARTHQUAKE': '🌍',
  'CYCLONE': '🌀',
  'DROUGHT': '☀️',
  'VOLCANO': '🌋',
  'WILDFIRE': '🔥',
  'STORM': '⛈️',
  'TSUNAMI': '🌊',
  'DEFAULT': '⚠️'
};

// ============================================
// MAP INITIALIZATION
// ============================================
let map;
let countryMarkers;
let hazardMarkers;

let mapInitialized = false;

function initMap() {
  if (mapInitialized) {
    // Just invalidate size if already initialized
    if (map) map.invalidateSize();
    return;
  }

  const mapContainer = document.getElementById('map');
  if (!mapContainer) return;

  // Check if container is visible (has dimensions)
  const rect = mapContainer.getBoundingClientRect();
  if (rect.width === 0 || rect.height === 0) {
    // Container is hidden, defer initialization
    console.log('Map container hidden, deferring initialization...');
    return;
  }

  map = L.map('map', {
    center: MapConfig.center,
    zoom: MapConfig.zoom,
    minZoom: MapConfig.minZoom,
    maxZoom: MapConfig.maxZoom,
    worldCopyJump: true,
    zoomControl: true,
    attributionControl: false
  });

  // Dark tile layer
  L.tileLayer(MapConfig.tileUrl, {
    attribution: MapConfig.tileAttribution,
    subdomains: 'abcd',
    maxZoom: 19
  }).addTo(map);

  // Initialize layer groups
  countryMarkers = L.layerGroup().addTo(map);
  hazardMarkers = L.layerGroup().addTo(map);

  // Load data
  loadCountries();
  loadHazards();

  mapInitialized = true;

  // Auto-refresh every 5 minutes
  setInterval(() => {
    loadCountries();
    loadHazards();
  }, 5 * 60 * 1000);
}

// ============================================
// COUNTRY MARKERS
// ============================================
async function loadCountries() {
  try {
    const response = await fetch('/api/countries');
    const countries = await response.json();

    countryMarkers.clearLayers();

    countries.forEach((country, index) => {
      if (country.latitude && country.longitude) {
        const color = SeverityColors[country.alertLevel] || SeverityColors['NO_DATA'];
        const radius = calculateRadius(country.peoplePhase3to5);

        // Add glow class based on severity
        const severityClass = country.alertLevel === 'CRITICAL' ? 'marker-critical pulse-marker' :
                              country.alertLevel === 'HIGH' ? 'marker-high' : 'marker-default';

        const marker = L.circleMarker([country.latitude, country.longitude], {
          radius: radius,
          fillColor: color,
          color: 'rgba(255, 255, 255, 0.5)',
          weight: 1.5,
          opacity: 1,
          fillOpacity: 0.75,
          className: `country-marker ${severityClass}`
        });

        // Animated entrance (staggered)
        marker.setStyle({ opacity: 0, fillOpacity: 0 });
        setTimeout(() => {
          marker.setStyle({ opacity: 1, fillOpacity: 0.75 });
        }, index * 10);

        marker.bindPopup(createCountryPopup(country), {
          className: 'modern-popup',
          maxWidth: 300
        });

        // Hover effects
        marker.on('mouseover', function() {
          this.setStyle({
            weight: 3,
            fillOpacity: 0.9
          });
          this.setRadius(radius * 1.2);
        });

        marker.on('mouseout', function() {
          this.setStyle({
            weight: 1.5,
            fillOpacity: 0.75
          });
          this.setRadius(radius);
        });

        countryMarkers.addLayer(marker);
      }
    });
  } catch (error) {
    console.error('Error loading countries:', error);
  }
}

function calculateRadius(peopleAffected) {
  if (!peopleAffected) return 5;
  if (peopleAffected > 15000000) return 24;
  if (peopleAffected > 10000000) return 20;
  if (peopleAffected > 5000000) return 16;
  if (peopleAffected > 1000000) return 12;
  if (peopleAffected > 100000) return 8;
  return 6;
}

function createCountryPopup(country) {
  const alertColor = SeverityColors[country.alertLevel] || SeverityColors['NO_DATA'];
  const peopleFormatted = country.peoplePhase3to5
    ? formatNumber(country.peoplePhase3to5)
    : 'N/A';
  const percentFormatted = country.percentPhase3to5
    ? country.percentPhase3to5.toFixed(1) + '%'
    : 'N/A';

  return `
    <div class="popup-content">
      <div class="popup-title">${country.name}</div>
      <div class="popup-divider"></div>
      <div class="popup-grid">
        <div class="popup-item">
          <span class="popup-label">Alert Level</span>
          <span class="popup-value" style="color: ${alertColor}">${country.alertLevel || 'N/A'}</span>
        </div>
        <div class="popup-item">
          <span class="popup-label">Food Insecure</span>
          <span class="popup-value">${peopleFormatted}</span>
        </div>
        <div class="popup-item">
          <span class="popup-label">Population %</span>
          <span class="popup-value">${percentFormatted}</span>
        </div>
        ${country.ipcAnalysisPeriod ? `
        <div class="popup-item full-width">
          <span class="popup-label">Analysis Period</span>
          <span class="popup-value">${country.ipcAnalysisPeriod}</span>
        </div>
        ` : ''}
      </div>
    </div>
  `;
}

// ============================================
// HAZARD MARKERS
// ============================================
async function loadHazards() {
  try {
    const response = await fetch('/api/hazards');
    const hazards = await response.json();

    hazardMarkers.clearLayers();

    hazards.forEach((hazard, index) => {
      if (hazard.latitude && hazard.longitude) {
        const emoji = HazardEmoji[hazard.type] || HazardEmoji['DEFAULT'];

        const marker = L.marker([hazard.latitude, hazard.longitude], {
          icon: L.divIcon({
            className: 'hazard-marker',
            html: `
              <div class="hazard-marker-inner ${hazard.severity?.toLowerCase() || ''}">
                <span class="hazard-emoji">${emoji}</span>
              </div>
            `,
            iconSize: [36, 36],
            iconAnchor: [18, 18]
          })
        });

        marker.bindPopup(createHazardPopup(hazard), {
          className: 'modern-popup',
          maxWidth: 280
        });

        hazardMarkers.addLayer(marker);
      }
    });
  } catch (error) {
    console.error('Error loading hazards:', error);
  }
}

function createHazardPopup(hazard) {
  const emoji = HazardEmoji[hazard.type] || HazardEmoji['DEFAULT'];
  const severityClass = hazard.severity?.toLowerCase() || '';

  return `
    <div class="popup-content">
      <div class="popup-title">${emoji} ${hazard.name}</div>
      <div class="popup-divider"></div>
      <div class="popup-tags">
        <span class="popup-tag">${hazard.type}</span>
        <span class="popup-tag ${severityClass}">${hazard.severity}</span>
      </div>
      ${hazard.description ? `<p class="popup-description">${hazard.description}</p>` : ''}
    </div>
  `;
}

// ============================================
// UTILITIES
// ============================================
function formatNumber(num) {
  if (num >= 1000000) {
    return (num / 1000000).toFixed(1) + 'M';
  }
  if (num >= 1000) {
    return (num / 1000).toFixed(0) + 'K';
  }
  return num.toString();
}

// ============================================
// CUSTOM STYLES (injected)
// ============================================
const mapStyles = document.createElement('style');
mapStyles.textContent = `
  .country-marker {
    transition: all 0.2s cubic-bezier(0.34, 1.56, 0.64, 1);
  }

  .hazard-marker {
    filter: drop-shadow(0 2px 8px rgba(0, 0, 0, 0.4));
  }

  .hazard-marker-inner {
    width: 36px;
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: rgba(28, 28, 36, 0.9);
    backdrop-filter: blur(10px);
    border-radius: 50%;
    border: 2px solid rgba(255, 255, 255, 0.2);
    animation: hazard-pulse 2s ease-in-out infinite;
  }

  .hazard-marker-inner.warning {
    border-color: #ff453a;
    box-shadow: 0 0 20px rgba(255, 69, 58, 0.4);
  }

  .hazard-marker-inner.watch {
    border-color: #ff9f0a;
    box-shadow: 0 0 20px rgba(255, 159, 10, 0.4);
  }

  .hazard-emoji {
    font-size: 18px;
  }

  @keyframes hazard-pulse {
    0%, 100% { transform: scale(1); }
    50% { transform: scale(1.1); }
  }

  .modern-popup .leaflet-popup-content-wrapper {
    background: rgba(28, 28, 36, 0.95);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border-radius: 16px;
    border: 1px solid rgba(255, 255, 255, 0.1);
    box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
    padding: 0;
  }

  .modern-popup .leaflet-popup-content {
    margin: 0;
    color: #fff;
  }

  .modern-popup .leaflet-popup-tip {
    background: rgba(28, 28, 36, 0.95);
  }

  .popup-content {
    padding: 16px;
  }

  .popup-title {
    font-size: 1.1rem;
    font-weight: 600;
    margin-bottom: 8px;
  }

  .popup-divider {
    height: 1px;
    background: rgba(255, 255, 255, 0.1);
    margin: 12px 0;
  }

  .popup-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
  }

  .popup-item {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .popup-item.full-width {
    grid-column: span 2;
  }

  .popup-label {
    font-size: 0.7rem;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: rgba(255, 255, 255, 0.5);
  }

  .popup-value {
    font-size: 0.95rem;
    font-weight: 500;
  }

  .popup-tags {
    display: flex;
    gap: 8px;
    margin-bottom: 12px;
  }

  .popup-tag {
    padding: 4px 10px;
    background: rgba(255, 255, 255, 0.1);
    border-radius: 6px;
    font-size: 0.75rem;
    font-weight: 500;
    text-transform: uppercase;
  }

  .popup-tag.warning {
    background: rgba(255, 69, 58, 0.2);
    color: #ff453a;
  }

  .popup-tag.watch {
    background: rgba(255, 159, 10, 0.2);
    color: #ff9f0a;
  }

  .popup-description {
    font-size: 0.85rem;
    color: rgba(255, 255, 255, 0.7);
    line-height: 1.5;
  }
`;
document.head.appendChild(mapStyles);

// ============================================
// INITIALIZATION
// ============================================
document.addEventListener('DOMContentLoaded', initMap);

// Export for global access
window.CrisisMap = {
  init: initMap,
  loadCountries,
  loadHazards,
  map: () => map,
  invalidateSize: () => {
    if (map) {
      map.invalidateSize();
    }
  }
};
