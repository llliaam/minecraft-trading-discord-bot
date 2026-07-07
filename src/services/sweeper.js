// Sweeper: job periodik + startup reconciliation untuk E4.
// Tanggung jawab:
//   1. Startup: sweep listing RESERVED yang sudah expired (bot mati saat expired).
//   2. Periodik (tiap 5 menit): sweep listing RESERVED yang baru expired.
// Kedua alur memanggil expireReservations() yang sama.
import { db } from "../lib/db.js";
import { buildListingEmbed, buildListingButtons } from "../lib/embeds.js";
import { getMarketplaceChannel } from "../lib/marketplace.js";

const SWEEP_INTERVAL_MS = 5 * 60 * 1000; // 5 menit

/**
 * Expire semua listing RESERVED yang reservedUntil < sekarang.
 * Balikkan ke ACTIVE, bersihkan reservedFor/reservedUntil.
 * Kembalikan daftar listing yang di-expire beserta reservedFor-nya
 * (untuk notifikasi Discord).
 *
 * @returns {Promise<Array<{listing: object, reservedFor: string}>>}
 */
export async function expireReservations() {
  const now = new Date();

  // Ambil dulu listing yang akan di-expire (butuh reservedFor untuk notifikasi).
  const expiring = await db.listing.findMany({
    where: {
      status: "RESERVED",
      reservedUntil: { lt: now },
    },
  });

  if (expiring.length === 0) return [];

  const ids = expiring.map((l) => l.id);

  // Atomik: update semua sekaligus.
  await db.listing.updateMany({
    where: { id: { in: ids }, status: "RESERVED" },
    data: {
      status: "ACTIVE",
      reservedFor: null,
      reservedUntil: null,
    },
  });

  // Baca ulang agar state yang dikembalikan akurat.
  const updated = await db.listing.findMany({ where: { id: { in: ids } } });
  const byId = Object.fromEntries(updated.map((l) => [l.id, l]));

  return expiring.map((orig) => ({
    listing: byId[orig.id] ?? { ...orig, status: "ACTIVE", reservedFor: null, reservedUntil: null },
    reservedFor: orig.reservedFor,
  }));
}

/**
 * Sync embed Discord + kirim notifikasi untuk listing yang baru saja di-expire.
 * @param {import("discord.js").Client} client
 * @param {Array<{listing: object, reservedFor: string}>} expired
 */
async function notifyExpired(client, expired) {
  const channel = await getMarketplaceChannel(client);

  for (const { listing, reservedFor } of expired) {
    // Update embed listing → kembali hijau "Tersedia".
    if (listing.messageId && channel) {
      const msg = await channel.messages.fetch(listing.messageId).catch(() => null);
      if (msg) {
        await msg
          .edit({
            embeds: [buildListingEmbed(listing)],
            components: buildListingButtons(listing),
          })
          .catch(() => {});
      }
    }

    // Ping pembeli yang melewati batas waktu.
    if (channel && reservedFor) {
      await channel
        .send({
          content:
            `⏰ <@${reservedFor}> Reservasi listing **#${listing.id}** ` +
            `(**${listing.itemLabel}**) sudah kedaluwarsa karena kamu belum membayar ` +
            `dalam 24 jam. Listing ini kembali tersedia untuk umum.`,
        })
        .catch(() => {});
    }
  }
}

/**
 * Jalankan satu siklus sweep (expire + notifikasi).
 * Aman dipanggil berulang (idempoten — hanya expire yang benar-benar sudah lewat).
 * @param {import("discord.js").Client} client
 */
export async function runSweep(client) {
  try {
    const expired = await expireReservations();
    if (expired.length > 0) {
      console.log(`🧹 Sweeper: ${expired.length} reservasi kedaluwarsa, dikembalikan ke ACTIVE.`);
      await notifyExpired(client, expired);
    }
  } catch (err) {
    console.error("❌ Sweeper error:", err);
  }
}

/**
 * Mulai sweeper periodik (dipanggil sekali saat bot ready).
 * @param {import("discord.js").Client} client
 * @returns {NodeJS.Timeout} interval handle (untuk cleanup bila perlu).
 */
export function startSweeper(client) {
  // Langsung sweep saat startup (startup reconciliation E4-5).
  runSweep(client);

  // Lanjut tiap 5 menit.
  const handle = setInterval(() => runSweep(client), SWEEP_INTERVAL_MS);
  console.log(`✅ Sweeper reservasi aktif (interval ${SWEEP_INTERVAL_MS / 60000} menit).`);
  return handle;
}
