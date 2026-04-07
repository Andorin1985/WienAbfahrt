const CACHE_NAME = 'linien-offline-v1';
const OFFLINE_URL = '/linien/offline.html';

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(c => c.addAll([OFFLINE_URL]))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

// Netzwerk-first für alles; bei Navigations-Requests offline.html
self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);

  // Sicherheit: Niemals http vom eigenen Host (sollte ohnehin nie vorkommen)
  if (url.origin === 'http://halo.habith.eu') {
    event.respondWith(new Response('HTTP blocked', { status: 400 }));
    return;
  }

  // Navigations-Requests: Offline-Fallback
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req).catch(() => caches.match(OFFLINE_URL))
    );
    return;
  }

  // Standard: Netzwerk-first, kein persistentes Caching
  event.respondWith(
    fetch(req).catch(() => caches.match(req))
  );
});
