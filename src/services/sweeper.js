// Sweeper: job periodik + startup reconciliation.
// Tanggung jawab:
//   1. Startup: sweep listing RESERVED/ACTIVE yang sudah expired.
//   2. Periodik (tiap 5 menit): sweep yang baru expired.
import { db } from "../lib/db.js";
import { buildListingEmbed, buildListingButtons } from "../lib/embeds.js";
import { getMarketplaceChannel } from "../lib/marketplace.js";
import { pushToMod } from "../api/ws.js";
import { getLinkByDiscord } from "./linkService.js";

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

// ─── Auto-expire listing ACTIVE ──────────────────────────────────────────────

/**
 * Expire semua listing ACTIVE/RESERVED yang expiresAt < sekarang.
 * - Listing BUY: status → EXPIRED langsung.
 * - Listing SELL dengan escrowRef: status → EXPIRED + buat MailboxItem(CANCELLED_RETURN)
 *   agar penjual bisa klaim barang in-game (item tetap di escrow mod).
 * @returns {Promise<object[]>} listing yang baru di-expire.
 */
export async function expireListings() {
  const now = new Date();

  const expiring = await db.listing.findMany({
    where: {
      status: { in: ["ACTIVE", "RESERVED"] },
      expiresAt: { lt: now },
    },
  });

  if (expiring.length === 0) return [];

  // Pisah: SELL bereskrow vs lainnya.
  const withEscrow = expiring.filter((l) => l.escrowRef);
  const withoutEscrow = expiring.filter((l) => !l.escrowRef);

  // Listing tanpa escrow (BUY, atau SELL belum sempat setor): langsung EXPIRED.
  if (withoutEscrow.length > 0) {
    await db.listing.updateMany({
      where: { id: { in: withoutEscrow.map((l) => l.id) } },
      data: { status: "EXPIRED" },
    });
  }

  // Listing SELL dengan escrow: expire + buat mailbox item.
  for (const listing of withEscrow) {
    await db.$transaction(async (tx) => {
      const claimed = await tx.listing.updateMany({
        where: { id: listing.id, status: { in: ["ACTIVE", "RESERVED"] } },
        data: { status: "EXPIRED" },
      });
      if (claimed.count === 0) return; // balapan, sudah berubah status lain
      await tx.mailboxItem.create({
        data: {
          ownerId: listing.creatorId,
          escrowRef: listing.escrowRef,
          itemKey: listing.itemKey,
          itemLabel: listing.itemLabel,
          quantity: listing.quantity,
          reason: "CANCELLED_RETURN",
        },
      });
    });
  }

  return expiring;
}

/**
 * Notifikasi Discord untuk listing yang baru auto-expire.
 * @param {import("discord.js").Client} client
 * @param {object[]} expired  daftar listing (sudah EXPIRED di DB).
 */
async function notifyExpiredListings(client, expired) {
  const channel = await getMarketplaceChannel(client);

  for (const listing of expired) {
    // Update embed → abu-abu "Kadaluarsa", tanpa tombol.
    const expiredListing = { ...listing, status: "EXPIRED" };
    if (listing.messageId && channel) {
      const msg = await channel.messages.fetch(listing.messageId).catch(() => null);
      if (msg) {
        await msg
          .edit({
            embeds: [buildListingEmbed(expiredListing)],
            components: [],
          })
          .catch(() => {});
      }
    }

    // Ping creator — jika SELL, infokan barang ada di mailbox.
    if (channel) {
      const isSell = listing.type === "SELL" && listing.escrowRef;
      const note = isSell
        ? " Barang SELL-mu tersimpan di mailbox — klaim in-game dengan `/myclaim`."
        : "";
      await channel
        .send({
          content:
            `⏳ <@${listing.creatorId}> Listing **#${listing.id}** (**${listing.itemLabel}**) ` +
            `sudah kadaluarsa setelah 30 hari.${note}`,
        })
        .catch(() => {});
    }

    // Push notif in-game ke creator.
    const link = await getLinkByDiscord(listing.creatorId);
    if (link?.minecraftUuid) {
      pushToMod("LISTING_EXPIRED", {
        targetUuid: link.minecraftUuid,
        listingId: listing.id,
        itemLabel: listing.itemLabel,
        hasMail: !!(listing.type === "SELL" && listing.escrowRef),
      });
    }
  }
}

// ─── Siklus sweep ────────────────────────────────────────────────────────────

/**
 * Jalankan satu siklus sweep (expire reservasi + expire listing lama + notifikasi).
 * Aman dipanggil berulang (idempoten).
 * @param {import("discord.js").Client} client
 */
export async function runSweep(client) {
  try {
    const expiredReservations = await expireReservations();
    if (expiredReservations.length > 0) {
      console.log(`🧹 Sweeper: ${expiredReservations.length} reservasi kedaluwarsa → ACTIVE.`);
      await notifyExpired(client, expiredReservations);
    }

    const expiredListings = await expireListings();
    if (expiredListings.length > 0) {
      console.log(`🧹 Sweeper: ${expiredListings.length} listing kadaluarsa → EXPIRED.`);
      await notifyExpiredListings(client, expiredListings);
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
