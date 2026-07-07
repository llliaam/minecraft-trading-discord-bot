// Logika bisnis untuk Listing. Handler memanggil fungsi-fungsi ini;
// handler TIDAK mengakses Prisma langsung (lihat CLAUDE.md).
import { db } from "../lib/db.js";
import { BusinessError } from "./transactionService.js";

/**
 * Buat listing baru (status default ACTIVE).
 * @param {object} input
 * @param {string} input.creatorId      Discord user ID pembuat.
 * @param {"SELL"|"BUY"} input.type
 * @param {string} input.itemKey        id kanonik MC, mis. "minecraft:elytra".
 * @param {string} input.itemLabel      teks tampilan, mis. "Elytra".
 * @param {number} input.quantity
 * @param {string} input.priceItemKey   id item pembayaran, mis. "minecraft:diamond".
 * @param {number} input.priceQuantity  jumlah item pembayaran.
 * @param {string|null} [input.description]
 * @param {string|null} [input.escrowRef]  slot escrow di mod (untuk SELL yang
 *                                          barangnya sudah disetor — Fase E).
 * @returns {Promise<object>} record Listing.
 */
const LISTING_TTL_DAYS = 30;

export function createListing(input) {
  const expiresAt = new Date(Date.now() + LISTING_TTL_DAYS * 24 * 60 * 60 * 1000);
  return db.listing.create({
    data: {
      creatorId: input.creatorId,
      type: input.type,
      itemKey: input.itemKey,
      itemLabel: input.itemLabel,
      quantity: input.quantity,
      priceItemKey: input.priceItemKey,
      priceQuantity: input.priceQuantity,
      description: input.description ?? null,
      escrowRef: input.escrowRef ?? null,
      expiresAt,
    },
  });
}

/**
 * Simpan ID pesan embed marketplace ke listing, agar embed bisa di-edit
 * saat status berubah (mis. jadi SOLD).
 */
export function attachMessageId(listingId, messageId) {
  return db.listing.update({
    where: { id: listingId },
    data: { messageId },
  });
}

/** Ambil satu listing by id (atau null). */
export function getListing(listingId) {
  return db.listing.findUnique({ where: { id: listingId } });
}

/** Jumlah listing per halaman untuk /browse & /search. */
export const PAGE_SIZE = 5;

/**
 * Ambil listing ACTIVE dengan pagination, terbaru lebih dulu.
 * @param {object} [opts]
 * @param {"SELL"|"BUY"} [opts.type]  filter tipe (opsional).
 * @param {number} [opts.page=1]      halaman 1-based.
 * @returns {Promise<{items: object[], total: number, page: number, totalPages: number}>}
 */
export async function browseListings({ type, page = 1 } = {}) {
  // ACTIVE dan RESERVED keduanya tampil di marketplace (RESERVED terkunci untuk 1 pembeli).
  const where = { status: { in: ["ACTIVE", "RESERVED"] } };
  if (type) where.type = type;

  const total = await db.listing.count({ where });
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const safePage = Math.min(Math.max(1, page), totalPages);

  const rawItems = await db.listing.findMany({
    where,
    orderBy: { createdAt: "desc" },
    skip: (safePage - 1) * PAGE_SIZE,
    take: PAGE_SIZE,
  });

  const items = await attachCreatorNames(rawItems);
  return { items, total, page: safePage, totalPages };
}

/**
 * Cari listing ACTIVE berdasarkan nama item (case-insensitive, partial match).
 * Hasil ditampilkan satu halaman (maksimal PAGE_SIZE * batas wajar).
 * @param {object} opts
 * @param {string} opts.query          kata kunci nama item.
 * @param {"SELL"|"BUY"} [opts.type]   filter tipe (opsional).
 * @param {number} [opts.limit=10]     maksimal hasil ditampilkan.
 * @returns {Promise<{items: object[], total: number, page: number, totalPages: number}>}
 */
export async function searchListings({ query, type, limit = 10 }) {
  // Catatan: SQLite tidak mendukung `mode: "insensitive"`. Untuk teks ASCII,
  // LIKE di SQLite sudah case-insensitive secara default, jadi cukup `contains`.
  // Cari di itemLabel (teks tampilan) — lebih ramah pengguna daripada itemKey.
  const where = {
    status: { in: ["ACTIVE", "RESERVED"] },
    itemLabel: { contains: query },
  };
  if (type) where.type = type;

  const total = await db.listing.count({ where });
  const rawItems = await db.listing.findMany({
    where,
    orderBy: { createdAt: "desc" },
    take: limit,
  });

  const items = await attachCreatorNames(rawItems);
  // Bentuk return disamakan dengan browseListings agar bisa pakai buildListEmbed.
  return { items, total, page: 1, totalPages: 1 };
}

/**
 * Ambil listing milik seorang user yang masih relevan untuk dikelola
 * (ACTIVE & PENDING), terbaru lebih dulu. Listing yang sudah
 * COMPLETED/CANCELLED/EXPIRED dianggap selesai dan disembunyikan.
 * @param {string} creatorId  Discord user ID.
 * @returns {Promise<object[]>} daftar record Listing.
 */
export async function getMyListings(creatorId) {
  const items = await db.listing.findMany({
    where: { creatorId, status: { in: ["ACTIVE", "PENDING", "RESERVED"] } },
    orderBy: { createdAt: "desc" },
  });
  return attachCreatorNames(items);
}

/**
 * Enrichment helper: tambahkan `creatorName` (minecraftName dari PlayerLink)
 * ke tiap listing. Bila pemain belum linked, `creatorName` = null.
 * Batch: satu query ke PlayerLink untuk semua creatorId unik.
 */
async function attachCreatorNames(listings) {
  if (listings.length === 0) return listings;

  const ids = [...new Set(listings.map((l) => l.creatorId))];
  const links = await db.playerLink.findMany({
    where: { discordId: { in: ids } },
    select: { discordId: true, minecraftName: true },
  });

  const nameMap = Object.fromEntries(links.map((l) => [l.discordId, l.minecraftName]));
  return listings.map((l) => ({ ...l, creatorName: nameMap[l.creatorId] ?? null }));
}

/**
 * Batalkan sebuah listing. Hanya creator yang boleh, dan hanya listing
 * yang masih ACTIVE (yang sudah PENDING berarti ada deal berjalan — tidak
 * boleh dibatalkan sepihak agar pihak lawan tidak dirugikan).
 *
 * Atomik: klaim dengan updateMany berfilter status ACTIVE supaya tidak
 * balapan dengan Buy Now / Accept offer yang sedang berjalan.
 *
 * <p><b>Barang SELL (escrowRef ada)</b> ditangani berdasarkan `returnMode`:
 * <ul>
 *   <li><b>"ingame"</b> — pemain online (via mod). Node cuma melepas metadata
 *       & mengembalikan `escrowRef`; mod yang menarik barang dari ledger.</li>
 *   <li><b>"mailbox"</b> — via Discord (pemain bisa offline). Barang TETAP di
 *       escrow; kita buat MailboxItem (CANCELLED_RETURN) yang menunjuk
 *       `escrowRef` sama, diklaim in-game nanti. Node tetap tak menyentuh item.</li>
 * </ul>
 * Untuk listing BUY (tanpa escrow) kedua mode sama: cukup batalkan metadata.
 *
 * @param {object} input
 * @param {number} input.listingId
 * @param {string} input.actorId   Discord user ID yang membatalkan.
 * @param {"ingame"|"mailbox"} [input.returnMode="mailbox"]
 * @returns {Promise<{listing: object, escrowRef: string|null}>}
 *          `escrowRef` non-null HANYA pada mode "ingame" bila ada barang untuk
 *          ditarik mod; null selain itu.
 * @throws {BusinessError}
 */
export async function cancelListing({ listingId, actorId, returnMode = "mailbox" }) {
  return db.$transaction(async (tx) => {
    const listing = await tx.listing.findUnique({ where: { id: listingId } });

    if (!listing) throw new BusinessError("Listing tidak ditemukan.");
    if (listing.creatorId !== actorId) {
      throw new BusinessError("Hanya pembuat listing yang bisa membatalkannya.");
    }
    if (listing.status === "PENDING") {
      throw new BusinessError(
        "Listing ini sudah ada deal berjalan dan tidak bisa dibatalkan. " +
          "Selesaikan trade-nya, atau koordinasikan dengan pihak lawan.",
      );
    }
    if (listing.status !== "ACTIVE" && listing.status !== "RESERVED") {
      throw new BusinessError("Listing ini sudah tidak aktif.");
    }

    // Klaim: berhasil jika masih ACTIVE atau RESERVED (reservasi belum dibayar = boleh cancel).
    const claimed = await tx.listing.updateMany({
      where: { id: listingId, status: { in: ["ACTIVE", "RESERVED"] } },
      data: { status: "CANCELLED" },
    });
    if (claimed.count === 0) {
      throw new BusinessError("Listing ini baru saja berubah status.");
    }

    const cancelled = { ...listing, status: "CANCELLED" };

    // Tak ada barang di escrow (mis. BUY) → tak ada yang perlu dikembalikan.
    if (!listing.escrowRef) {
      return { listing: cancelled, escrowRef: null };
    }

    if (returnMode === "ingame") {
      // Pemain online: mod yang menarik barang. Serahkan escrowRef.
      return { listing: cancelled, escrowRef: listing.escrowRef };
    }

    // Mode mailbox (Discord/offline): barang tetap di escrow, dialihkan jadi
    // titipan mailbox milik penjual. Withdraw fisik terjadi saat /claim.
    await depositToMailboxTx(tx, {
      ownerId: listing.creatorId,
      escrowRef: listing.escrowRef,
      itemKey: listing.itemKey,
      itemLabel: listing.itemLabel,
      quantity: listing.quantity,
      reason: "CANCELLED_RETURN",
    });

    return { listing: cancelled, escrowRef: null };
  });
}

/** Versi transaksional depositToMailbox (dipakai di dalam $transaction cancel). */
function depositToMailboxTx(tx, { ownerId, escrowRef, itemKey, itemLabel, quantity, reason }) {
  return tx.mailboxItem.create({
    data: { ownerId, escrowRef, itemKey, itemLabel, quantity, reason },
  });
}
