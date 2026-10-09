const CACHE_NAME = "my-bookshelf-v16";
const APP_SHELL = ["./", "./index.html", "./manifest.json", "./icon-192.png", "./icon-512.png", "./bookshelf-background.jpg"];

function saveSharedFile(file) {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("my-bookshelf-db", 1);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains("files")) {
        db.close();
        reject(new Error("Bookshelf storage is unavailable"));
        return;
      }
      const tx = db.transaction("files", "readwrite");
      tx.objectStore("files").add({
        name: file.name || "Shared file",
        type: file.type || "application/octet-stream",
        size: file.size,
        added: Date.now(),
        category: "Other",
        blob: file
      });
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onerror = () => { db.close(); reject(tx.error); };
      tx.onabort = () => { db.close(); reject(tx.error || new Error("Could not save shared file")); };
    };
  });
}

self.addEventListener("install", event => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    await cache.addAll(APP_SHELL);
    // Cache the PDF.js engine when online so PDF reading can keep working offline.
    // These are optional: a CDN outage must not prevent the app shell from installing.
    await Promise.all([
      "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js",
      "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js"
    ].map(async url => {
      try {
        const response = await fetch(url, { mode: "cors", cache: "no-cache" });
        if (response.ok) await cache.put(url, response);
      } catch (_) {}
    }));
  })());
  self.skipWaiting();
});

self.addEventListener("activate", event => {
  event.waitUntil(caches.keys().then(keys =>
    Promise.all(keys.filter(k => k.startsWith("my-bookshelf-") && k !== CACHE_NAME).map(k => caches.delete(k)))
  ));
  self.clients.claim();
});

self.addEventListener("fetch", event => {
  const request = event.request;
  const url = new URL(request.url);

  if (request.method === "POST" && url.origin === self.location.origin &&
      (url.pathname.endsWith("/share-target/") || url.pathname.endsWith("/share-target"))) {
    event.respondWith((async () => {
      try {
        const form = await request.formData();
        const files = form.getAll("shared_files").filter(value => value instanceof File && value.size >= 0 && value.name);
        if (!files.length) {
          return Response.redirect(new URL("./?share-error=no-files", self.registration.scope).href, 303);
        }
        for (const file of files) await saveSharedFile(file);
        return Response.redirect(new URL("./?shared=" + files.length, self.registration.scope).href, 303);
      } catch (error) {
        return Response.redirect(new URL("./?share-error=save-failed", self.registration.scope).href, 303);
      }
    })());
    return;
  }

  if (request.method !== "GET") return;
  event.respondWith(caches.match(request).then(cached =>
    cached || fetch(request).then(response => {
      if (response && response.ok && new URL(request.url).origin === self.location.origin) {
        const copy = response.clone();
        caches.open(CACHE_NAME).then(cache => cache.put(request, copy));
      }
      return response;
    }).catch(() => caches.match("./index.html"))
  ));
});
