'use strict';
/* 서비스 워커 (홈 화면에 설치한 앱용). 오프라인 안내만 맡는다.
 * - 모든 요청은 서버로 그대로 보낸다. 화면도 데이터도 저장해 두지 않는다
 *   (저장본 화면은 데이터를 못 불러와 빈 화면처럼 보이고, 가계부 숫자가 옛 값으로 보이면 안 되므로).
 * - 화면 이동이 실패하면(인터넷 끊김·서버 중단) offline.html 을 보여 준다. 그 화면에 필요한 파일만 미리 저장한다.
 * 저장 목록이 바뀌면 VERSION 을 올린다 (예전 저장본은 activate 때 지운다).
 */
const VERSION = 'haru-v3';
const OFFLINE = ['/offline.html', '/style.css', '/theme.js', '/favicon.svg'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(OFFLINE)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  if (req.mode === 'navigate') {
    e.respondWith(fetch(req).catch(() => caches.match('/offline.html')));
  } else if (OFFLINE.includes(new URL(req.url).pathname)) {
    // 오프라인 안내 화면이 쓰는 파일: 서버가 안 되면 저장본
    e.respondWith(fetch(req).catch(() => caches.match(req, { ignoreSearch: true })));
  }
});
