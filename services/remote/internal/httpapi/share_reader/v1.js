"use strict";

const status = document.getElementById("status");
const snapshot = document.getElementById("snapshot");
const maxBytes = 2 * 1024 * 1024;

async function openSnapshot() {
  const match = /^#v1=([A-Za-z0-9_-]{43})$/.exec(location.hash);
  if (!match) {
    status.textContent = "链接缺少解密密钥。请使用发送者提供的完整链接或二维码。";
    return;
  }
  if (!globalThis.crypto?.subtle) {
    status.textContent = "此浏览器无法安全解密。请使用支持 Web Crypto 的现代浏览器。";
    return;
  }
  const raw = Uint8Array.from(atob(match[1].replace(/-/g, "+").replace(/_/g, "/") + "="), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["decrypt"]);
  raw.fill(0);
  // Use the resource pathname only: never send the fragment or any credentials.
  const response = await fetch(location.pathname + "/ciphertext", {
    method: "GET", credentials: "omit", redirect: "error", mode: "same-origin", cache: "no-store", referrerPolicy: "no-referrer",
  });
  if (response.status === 404) {
    status.textContent = "此会话分享不存在或已过期。";
    return;
  }
  const length = Number(response.headers.get("Content-Length"));
  if (!response.ok || response.headers.get("Content-Type") !== "application/octet-stream" || !Number.isSafeInteger(length) || length < 29 || length > maxBytes) {
    throw new Error("invalid share response");
  }
  const ciphertext = new Uint8Array(await response.arrayBuffer());
  if (ciphertext.length !== length || ciphertext[0] !== 1) throw new Error("invalid share format");
  const plaintext = await crypto.subtle.decrypt({
    name: "AES-GCM", iv: ciphertext.subarray(1, 13), tagLength: 128,
    additionalData: new TextEncoder().encode("tokenworks.session-share.v1"),
  }, key, ciphertext.subarray(13));
  // The exported text is never interpreted as HTML, Markdown, a URL or a command.
  snapshot.textContent = new TextDecoder("utf-8", { fatal: true }).decode(plaintext);
  snapshot.hidden = false;
  status.textContent = "快照已在此浏览器中解密。此页面不会连接原会话。";
}

openSnapshot().catch(() => {
  status.textContent = "无法打开快照。请核对完整链接，或稍后重试。";
});
