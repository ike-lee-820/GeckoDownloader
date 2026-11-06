"use strict";

// Matches on MIME type and URL suffix. Same lists as the native side expects.
const MIME_HITS = [
  "application/vnd.apple.mpegurl",
  "application/x-mpegurl",
  "video/mp2t",
  "application/dash+xml",
  "video/",
  "audio/"
];
const EXT_HITS = [".m3u8", ".ts", ".mp4", ".m4s", ".mpd", ".flv", ".webm", ".mp3", ".m4a"];

// requestId -> { method, url, headers, body, ts }
const pending = new Map();
const PENDING_LIMIT = 2000;

function extOf(url) {
  const clean = url.split("?")[0].split("#")[0].toLowerCase();
  const dot = clean.lastIndexOf(".");
  return dot >= 0 ? clean.slice(dot) : "";
}

function isInteresting(url, contentType) {
  if (contentType) {
    const ct = contentType.toLowerCase();
    if (MIME_HITS.some(m => ct.startsWith(m))) return true;
  }
  return EXT_HITS.includes(extOf(url));
}

function remember(id, entry) {
  pending.set(id, entry);
  if (pending.size > PENDING_LIMIT) {
    // Drop the oldest entries so a long session cannot grow without bound.
    const first = pending.keys().next().value;
    pending.delete(first);
  }
}

// Step 1: method, URL and body are available at onBeforeRequest. POST is included.
browser.webRequest.onBeforeRequest.addListener(
  details => {
    remember(details.requestId, {
      method: details.method,
      url: details.url,
      headers: {},
      bodyPresent: !!details.requestBody,
      tabId: details.tabId,
      originUrl: details.originUrl || details.documentUrl || "",
      ts: Date.now()
    });
    return {}; // Observe only: nothing is blocked or modified.
  },
  { urls: ["<all_urls>"] },
  ["requestBody", "blocking"]
);

// Step 2: request headers arrive at onBeforeSendHeaders (not onBeforeRequest in the
// WebExtension API). They are attached to the same requestId.
browser.webRequest.onBeforeSendHeaders.addListener(
  details => {
    const entry = pending.get(details.requestId);
    if (entry) {
      const headers = {};
      for (const h of details.requestHeaders || []) {
        headers[h.name] = h.value;
      }
      entry.headers = headers;
    }
    return { requestHeaders: details.requestHeaders };
  },
  { urls: ["<all_urls>"] },
  ["requestHeaders", "blocking"]
);

// Step 3: the response Content-Type decides if the resource is media.
browser.webRequest.onHeadersReceived.addListener(
  details => {
    const entry = pending.get(details.requestId);
    if (!entry) return {};
    let contentType = "";
    for (const h of details.responseHeaders || []) {
      if (h.name.toLowerCase() === "content-type") contentType = h.value;
    }
    if (isInteresting(details.url, contentType)) {
      const payload = {
        url: details.url,
        method: entry.method,
        headers: entry.headers,
        contentType: contentType,
        statusCode: details.statusCode,
        pageUrl: entry.originUrl,
        bodyPresent: entry.bodyPresent,
        tabId: entry.tabId,
        ts: entry.ts
      };
      browser.runtime.sendNativeMessage("sniffer", payload).catch(() => {});
    }
    pending.delete(details.requestId);
    return {};
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders", "blocking"]
);

// Clean up requests that never produced a response.
browser.webRequest.onErrorOccurred.addListener(
  details => { pending.delete(details.requestId); },
  { urls: ["<all_urls>"] }
);
