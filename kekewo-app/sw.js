// 앱 화면은 미리 저장해 두고, 글은 인터넷이 되면 새로 받고 안 되면 마지막으로 본 내용을 보여 준다.
const VERSION = 'kekewo-v1';
const SHELL = [
  './',
  'index.html',
  'style.css',
  'app.js',
  'manifest.webmanifest',
  'icons/icon.svg',
  'icons/icon-192.png',
  'icons/icon-512.png',
];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(VERSION).then(cache => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', event => {
  const { request } = event;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);

  if (url.origin === self.location.origin) {
    // 앱 화면: 새 버전을 먼저 시도하고, 실패하면 저장본
    event.respondWith(
      fetch(request)
        .then(res => {
          const copy = res.clone();
          caches.open(VERSION).then(cache => cache.put(request, copy));
          return res;
        })
        .catch(() => caches.match(request).then(hit => hit || caches.match('index.html'))),
    );
    return;
  }

  if (url.hostname === 'public-api.wordpress.com') {
    // 글 데이터: 인터넷 우선, 오프라인이면 저장본
    event.respondWith(
      fetch(request)
        .then(res => {
          if (res.ok) {
            const copy = res.clone();
            caches.open(VERSION + '-api').then(cache => cache.put(request, copy));
          }
          return res;
        })
        .catch(() => caches.open(VERSION + '-api').then(cache => cache.match(request))
          .then(hit => hit || Response.error())),
    );
  }
});
