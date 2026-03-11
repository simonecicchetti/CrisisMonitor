/**
 * Crisis Monitor - Service Worker v6
 * PWA offline support - static assets only
 * API calls are NOT cached to prevent stale data issues
 */

const CACHE_NAME = 'crisis-monitor-v6';
const STATIC_ASSETS = [
  '/',
  '/css/style.css',
  '/js/app.js',
  '/js/map.js',
  '/manifest.json',
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css',
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js',
  'https://cdn.jsdelivr.net/npm/chart.js'
];

// Install - cache static assets
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(STATIC_ASSETS))
  );
  self.skipWaiting();
});

// Activate - clean old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((names) =>
      Promise.all(names.filter((n) => n !== CACHE_NAME).map((n) => caches.delete(n)))
    )
  );
  self.clients.claim();
});

// Fetch handler
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Skip non-GET
  if (event.request.method !== 'GET') return;

  // API calls - BYPASS service worker completely (network only)
  // Let the frontend handle errors and caching via Redis
  if (url.pathname.startsWith('/api/')) {
    // Don't intercept - let browser handle directly
    return;
  }

  // External API calls - bypass
  if (!url.origin.includes(self.location.origin) && url.pathname.includes('/api')) {
    return;
  }

  // Static assets - stale-while-revalidate
  event.respondWith(
    caches.open(CACHE_NAME).then(async (cache) => {
      const cached = await cache.match(event.request);

      // Fetch in background and update cache
      const networkPromise = fetch(event.request)
        .then((response) => {
          if (response.ok) {
            cache.put(event.request, response.clone());
          }
          return response;
        })
        .catch(() => null);

      // Return cached immediately, or wait for network
      return cached || networkPromise || new Response('Offline', { status: 503 });
    })
  );
});

// Handle offline/online status
self.addEventListener('message', (event) => {
  if (event.data === 'skipWaiting') {
    self.skipWaiting();
  }
});
