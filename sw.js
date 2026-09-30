/*
 * VoiceScribe - Service Worker
 * Rende l'app installabile e utilizzabile anche offline (le API Groq restano
 * ovviamente online). Funziona solo se la pagina e' servita in http(s):
 * aprendo il file con file:// il browser non registra i service worker.
 */
const CACHE = 'voicescribe-v6';

// App shell + librerie da CDN (jsPDF e Chart.js vengono messe in cache al primo avvio)
const APP_SHELL = [
    './',
    './index.html',
    './native-bridge.js',
    './manifest.webmanifest',
    './icon.svg',
    'https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js',
    'https://cdn.jsdelivr.net/npm/chart.js@4.4.2/dist/chart.umd.min.js'
];

self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE)
            .then(cache => Promise.all(APP_SHELL.map(url => cache.add(url).catch(() => null))))
            .then(() => self.skipWaiting())
    );
});

self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys()
            .then(keys => Promise.all(keys.filter(key => key !== CACHE).map(key => caches.delete(key))))
            .then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', (event) => {
    const request = event.request;
    if (request.method !== 'GET') return;

    const url = new URL(request.url);

    // Le chiamate alle API (trascrizione e AI) non devono mai passare dalla cache
    if (url.hostname === 'api.groq.com') return;

    // Navigazione: prima la rete (per avere sempre l'ultima versione), poi la cache
    if (request.mode === 'navigate') {
        event.respondWith(
            fetch(request)
                .then(response => { cachePut(request, response.clone()); return response; })
                .catch(() => caches.match('./index.html').then(cached => cached || caches.match('./')))
        );
        return;
    }

    // Risorse: prima la cache (offline-first), altrimenti rete
    event.respondWith(
        caches.match(request).then(cached => {
            if (cached) return cached;
            return fetch(request)
                .then(response => { cachePut(request, response.clone()); return response; })
                .catch(() => cached);
        })
    );
});

function cachePut(request, response) {
    if (!response || !response.ok || response.type === 'opaque') return;
    caches.open(CACHE)
        .then(cache => cache.put(request, response))
        .catch(() => {});
}
