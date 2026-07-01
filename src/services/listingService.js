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
 * @returns {Promise<object>} record Listing.
 */
export function createListing(input) {
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
  const where = { status: "ACTIVE" };
  if (type) where.type = type;

  const total = await db.listing.count({ where });
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const safePage = Math.min(Math.max(1, page), totalPages);

  const items = await db.listing.findMany({
    where,
    orderBy: { createdAt: "desc" },
    skip: (safePage - 1) * PAGE_SIZE,
    take: PAGE_SIZE,
  });

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
    status: "ACTIVE",
    itemLabel: { contains: query },
  };
  if (type) where.type = type;

  const total = await db.listing.count({ where });
  const items = await db.listing.findMany({
    where,
    orderBy: { createdAt: "desc" },
    take: limit,
  });

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
export function getMyListings(creatorId) {
  return db.listing.findMany({
    where: { creatorId, status: { in: ["ACTIVE", "PENDING"] } },
    orderBy: { createdAt: "desc" },
  });
}

/**
 * Batalkan sebuah listing. Hanya creator yang boleh, dan hanya listing
 * yang masih ACTIVE (yang sudah PENDING berarti ada deal berjalan — tidak
 * boleh dibatalkan sepihak agar pihak lawan tidak dirugikan).
 *
 * Atomik: klaim dengan updateMany berfilter status ACTIVE supaya tidak
 * balapan dengan Buy Now / Accept offer yang sedang berjalan.
 *
 * @param {object} input
 * @param {number} input.listingId
 * @param {string} input.actorId   Discord user ID yang membatalkan.
 * @returns {Promise<{listing: object}>}
 * @throws {BusinessError}
 */
export async function cancelListing({ listingId, actorId }) {
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
    if (listing.status !== "ACTIVE") {
      throw new BusinessError("Listing ini sudah tidak aktif.");
    }

    // Klaim: hanya berhasil jika masih ACTIVE (anti balapan).
    const claimed = await tx.listing.updateMany({
      where: { id: listingId, status: "ACTIVE" },
      data: { status: "CANCELLED" },
    });
    if (claimed.count === 0) {
      throw new BusinessError("Listing ini baru saja berubah status.");
    }

    return { listing: { ...listing, status: "CANCELLED" } };
  });
}
