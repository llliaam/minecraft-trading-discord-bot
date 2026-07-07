// WebSocket server untuk push notifikasi dari Node ke Mod (satu arah: Node → Mod).
// Mod membuka satu koneksi WS saat startup; Node push event kapan saja.
//
// Event yang dikirim (format: JSON satu baris):
//   { event: "OFFER_RECEIVED",  data: { targetUuid, listingId, offerId, priceText } }
//   { event: "OFFER_ACCEPTED",  data: { targetUuid, listingId, offerId } }
//   { event: "OFFER_REJECTED",  data: { targetUuid, offerId } }
//   { event: "ITEM_SOLD",       data: { targetUuid, listingId, itemLabel, quantity } }
//   { event: "LISTING_EXPIRED", data: { targetUuid, listingId, itemLabel, hasMail } }
//
// `targetUuid` = UUID Minecraft penerima notifikasi (pemain yang harus di-ping in-game).
//
// Auth: Mod kirim header "Authorization: Bearer <API_SECRET>" saat upgrade WS.
// Server menolak koneksi tanpa / dengan token salah (status 401).
import { WebSocketServer } from "ws";
import { config } from "../lib/config.js";

/** Set semua koneksi mod yang sedang aktif (biasanya hanya 1). */
const connections = new Set();

/**
 * Inisialisasi WS server di atas http.Server yang sudah ada.
 * Dipanggil sekali dari startApiServer.
 * @param {import("node:http").Server} httpServer
 */
export function attachWsServer(httpServer) {
  const wss = new WebSocketServer({ noServer: true });

  // Upgrade HTTP → WS, validasi token di sini (sebelum handshake selesai).
  httpServer.on("upgrade", (req, socket, head) => {
    const authHeader = req.headers["authorization"] ?? "";
    if (authHeader !== `Bearer ${config.apiSecret}`) {
      socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws, req));
  });

  wss.on("connection", (ws) => {
    connections.add(ws);
    console.log(`🔌 Mod terhubung via WS. Koneksi aktif: ${connections.size}`);

    ws.on("close", () => {
      connections.delete(ws);
      console.log(`🔌 Koneksi WS mod putus. Koneksi aktif: ${connections.size}`);
    });

    ws.on("error", (err) => {
      console.error("❌ WS error:", err.message);
      connections.delete(ws);
    });

    // Mod tidak perlu kirim apa pun; abaikan pesan masuk.
    ws.on("message", () => {});
  });

  console.log("✅ WebSocket server (mod push notif) siap (memakai port REST yang sama).");
  return wss;
}

/**
 * Kirim event ke SEMUA koneksi mod aktif.
 * Fire-and-forget: gagal kirim ke satu koneksi tidak mempengaruhi lainnya.
 *
 * @param {string} event  nama event (mis. "OFFER_RECEIVED").
 * @param {object} data   payload event.
 */
export function pushToMod(event, data) {
  if (connections.size === 0) return; // mod tidak terhubung, lewati
  const payload = JSON.stringify({ event, data });
  for (const ws of connections) {
    if (ws.readyState === ws.OPEN) {
      ws.send(payload, (err) => {
        if (err) {
          console.error(`❌ Gagal push WS event "${event}":`, err.message);
          connections.delete(ws);
        }
      });
    }
  }
}
