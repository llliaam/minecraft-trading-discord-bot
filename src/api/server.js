// REST server lokal untuk mod Fabric (IPC Bot <-> Mod).
// Dibangun dengan node:http bawaan (tanpa Express) — kebutuhan kita kecil & lokal.
//
// Keamanan: semua endpoint (kecuali /health) butuh header
//   Authorization: Bearer <API_SECRET>
// Server hanya mendengar di localhost (playit.gg cuma tunnel port game 25565,
// jadi REST ini tidak pernah terekspos ke internet).
import { createServer } from "node:http";
import { config } from "../lib/config.js";
import { BusinessError } from "../services/transactionService.js";
import * as handlers from "./handlers.js";

// Tabel rute: "METHOD /path" -> handler. Path tanpa query string.
const ROUTES = {
  "GET /health": handlers.health,
  "GET /listings": handlers.listings,
  "POST /link/redeem": handlers.linkRedeem,
};

// Endpoint yang boleh diakses tanpa token (cuma health check).
const PUBLIC_ROUTES = new Set(["GET /health"]);

/** Baca seluruh body request jadi string (batasi ukuran biar aman). */
function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (c) => {
      size += c.length;
      if (size > 1_000_000) {
        // 1 MB cukup untuk payload kita; tolak yang kelewat besar.
        reject(new Error("Body terlalu besar."));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}


/** Kirim respons JSON. */
function sendJson(res, status, data) {
  const payload = JSON.stringify(data);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(payload),
  });
  res.end(payload);
}

/** Cek header Authorization cocok dengan API_SECRET. */
function isAuthorized(req) {
  const header = req.headers["authorization"] || "";
  const expected = `Bearer ${config.apiSecret}`;
  return header === expected;
}

async function handleRequest(req, res) {
  const url = new URL(req.url, "http://localhost");
  const routeKey = `${req.method} ${url.pathname}`;
  const handler = ROUTES[routeKey];

  if (!handler) {
    sendJson(res, 404, { error: "Endpoint tidak ditemukan." });
    return;
  }

  // Auth (kecuali rute publik).
  if (!PUBLIC_ROUTES.has(routeKey) && !isAuthorized(req)) {
    sendJson(res, 401, { error: "Tidak terotorisasi." });
    return;
  }

  // Parse query jadi objek biasa.
  const query = Object.fromEntries(url.searchParams.entries());

  // Parse body JSON (untuk POST/PUT). Body kosong -> {}.
  let body = {};
  if (req.method === "POST" || req.method === "PUT") {
    const raw = await readBody(req);
    if (raw) {
      try {
        body = JSON.parse(raw);
      } catch {
        sendJson(res, 400, { error: "Body bukan JSON valid." });
        return;
      }
    }
  }

  try {
    const result = await handler({ query, body, req });
    sendJson(res, result.status, result.data);
  } catch (err) {
    if (err instanceof BusinessError) {
      // Error bisnis (kode invalid, dll) -> 400 dengan pesan ramah.
      sendJson(res, 400, { error: err.message });
      return;
    }
    console.error("❌ Error di REST handler:", err);
    sendJson(res, 500, { error: "Kesalahan server internal." });
  }
}

/**
 * Nyalakan REST server. Mengembalikan instance http.Server (atau null bila
 * apiSecret belum di-set — server tak dinyalakan demi keamanan).
 */
export function startApiServer() {
  if (!config.apiSecret) {
    console.warn(
      "⚠️  API_SECRET belum di-set — REST server (untuk mod) TIDAK dinyalakan. " +
        "Isi API_SECRET di .env untuk mengaktifkan.",
    );
    return null;
  }

  const server = createServer((req, res) => {
    handleRequest(req, res).catch((err) => {
      console.error("❌ Error tak tertangani di REST server:", err);
      if (!res.headersSent) sendJson(res, 500, { error: "Kesalahan server internal." });
    });
  });

  // Dengar HANYA di loopback (127.0.0.1) — tidak terekspos ke jaringan.
  server.listen(config.apiPort, "127.0.0.1", () => {
    console.log(`✅ REST server (mod IPC) jalan di http://127.0.0.1:${config.apiPort}`);
  });

  server.on("error", (err) => {
    console.error(`❌ REST server gagal di port ${config.apiPort}:`, err.message);
  });

  return server;
}
