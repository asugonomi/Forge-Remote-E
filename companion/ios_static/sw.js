/* Only public, same-origin app resources belong in the offline cache. */
const shellCache = "forge-remote-e-ios-shell-v1";
const shellPaths = new Set([
  "/", "/manifest.webmanifest", "/icons/icon-192.png",
  "/icons/icon-512.png", "/icons/apple-touch-icon.png",
  "/app.css", "/editor.css", "/app.js", "/ui.js",
  "/extensions.js", "/generation.js", "/jobs.js", "/editor.js", "/gallery.js",
]);
self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(shellCache).then(cache => cache.addAll([...shellPaths])));
});
self.addEventListener("activate", (event) => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys
    .filter(key => key.startsWith("forge-remote-e-ios-shell-") && key !== shellCache)
    .map(key => caches.delete(key)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== self.location.origin ||
      url.search || !shellPaths.has(url.pathname)) return;
  event.respondWith((async () => {
    const cache = await caches.open(shellCache);
    try {
      const response = await fetch(event.request);
      if (response.ok && response.type === "basic") {
        await cache.put(event.request, response.clone());
      }
      return response;
    } catch (err) {
      const cached = await cache.match(event.request);
      if (cached) return cached;
      throw err;
    }
  })());
});
