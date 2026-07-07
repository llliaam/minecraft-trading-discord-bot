// Logika bisnis transaksi marketplace.
import { db } from "../lib/db.js";
import { prettify } from "../lib/itemCatalog.js";

/**
 * Error bisnis dengan pesan ramah untuk ditampilkan ke user.
 * Handler menangkap ini dan menampilkannya secara ephemeral.
 */
export class BusinessError extends Error {}

/**
 * Eksekusi pembelian escrow-aware dari in-game (Fase E2).
 *
 * Hanya untuk listing SELL. Pembeli sudah menyetor pembayaran ke escrow
 * (oleh mod) sebelum endpoint ini dipanggil. Node:
 * 1. Klaim listing ACTIVE→COMPLETED atomik (anti balapan).
 * 2. Buat Transaction(COMPLETED) langsung — swap atomik = settlement.
 * 3. Simpan MailboxItem SOLD_PAYMENT untuk penjual (bayaran menunggu diklaim).
 * 4. Kembalikan escrowRefToRelease (escrow barang-jual) agar mod menarik &
 *    menyerahkan barang ke pembeli. Node TAK menyentuh item.
 *
 * @param {object} input
 * @param {number} input.listingId
 * @param {string} input.buyerId         Discord user ID pembeli.
 * @param {string} input.paymentEscrowRef escrowRef slot pembayaran di mod.
 * @returns {Promise<{listing: object, transaction: object, escrowRefToRelease: string}>}
 * @throws {BusinessError}
 */
export async function purchaseListing({ listingId, buyerId, paymentEscrowRef }) {
  return db.$transaction(async (tx) => {
    const listing = await tx.listing.findUnique({ where: { id: listingId } });

    if (!listing) throw new BusinessError("Listing tidak ditemukan.");
    if (listing.type !== "SELL") {
      throw new BusinessError("Listing ini bukan tipe SELL. Gunakan sistem offer untuk listing BUY.");
    }
    if (listing.status !== "ACTIVE" && listing.status !== "RESERVED") {
      throw new BusinessError("Listing ini sudah tidak aktif (mungkin sudah dibeli orang lain).");
    }
    if (!listing.escrowRef) {
      throw new BusinessError("Listing ini belum punya barang di escrow. Hubungi admin.");
    }
    if (listing.creatorId === buyerId) {
      throw new BusinessError("Kamu tidak bisa membeli listing-mu sendiri.");
    }

    // Listing RESERVED: hanya pembeli yang sudah diterima offer-nya yang boleh bayar.
    if (listing.status === "RESERVED") {
      if (listing.reservedFor !== buyerId) {
        throw new BusinessError(
          "Listing ini sudah direservasi untuk pembeli lain. Hubungi penjual.",
        );
      }
      if (listing.reservedUntil && new Date(listing.reservedUntil) < new Date()) {
        throw new BusinessError(
          "Reservasi sudah kedaluwarsa. Listing ini kembali tersedia untuk umum.",
        );
      }
    }

    // Klaim atomik: berhasil jika masih ACTIVE atau RESERVED milik pembeli ini.
    // Kalau dua pembeli klik bersamaan, salah satu dapat count===0 → error ramah.
    const claimed = await tx.listing.updateMany({
      where: {
        id: listingId,
        status: { in: ["ACTIVE", "RESERVED"] },
      },
      data: { status: "COMPLETED" },
    });
    if (claimed.count === 0) {
      throw new BusinessError("Listing ini baru saja diambil orang lain. Coba listing lain.");
    }

    const now = new Date();
    const transaction = await tx.transaction.create({
      data: {
        listingId: listing.id,
        offerId: null,
        sellerId: listing.creatorId,
        buyerId,
        finalItemKey: listing.priceItemKey,
        finalQuantity: listing.priceQuantity,
        status: "COMPLETED",
        completedAt: now,
      },
    });

    // Pembayaran masuk mailbox penjual (bisa online/offline).
    await tx.mailboxItem.create({
      data: {
        ownerId: listing.creatorId,
        escrowRef: paymentEscrowRef,
        itemKey: listing.priceItemKey,
        itemLabel: prettify(listing.priceItemKey),
        quantity: listing.priceQuantity,
        reason: "SOLD_PAYMENT",
      },
    });

    return {
      listing: { ...listing, status: "COMPLETED" },
      transaction,
      escrowRefToRelease: listing.escrowRef,
    };
  });
}

/**
 * Tandai transaksi selesai (trade in-game beres).
 * Hanya seller atau buyer transaksi yang boleh.
 * Atomik: transaksi AGREED→COMPLETED, listing PENDING→COMPLETED.
 *
 * @param {object} input
 * @param {number} input.transactionId
 * @param {string} input.actorId   Discord user ID penekan tombol.
 * @returns {Promise<{listing: object, transaction: object}>}
 * @throws {BusinessError}
 */
export async function completeTransaction({ transactionId, actorId }) {
  return db.$transaction(async (tx) => {
    const transaction = await tx.transaction.findUnique({
      where: { id: transactionId },
      include: { listing: true },
    });

    if (!transaction) throw new BusinessError("Transaksi tidak ditemukan.");
    if (transaction.sellerId !== actorId && transaction.buyerId !== actorId) {
      throw new BusinessError("Hanya pihak yang terlibat yang bisa menandai selesai.");
    }
    if (transaction.status !== "AGREED") {
      throw new BusinessError("Transaksi ini sudah selesai.");
    }

    const now = new Date();
    await tx.transaction.update({
      where: { id: transaction.id },
      data: { status: "COMPLETED", completedAt: now },
    });
    await tx.listing.update({
      where: { id: transaction.listingId },
      data: { status: "COMPLETED" },
    });

    return {
      listing: { ...transaction.listing, status: "COMPLETED" },
      transaction: { ...transaction, status: "COMPLETED", completedAt: now },
    };
  });
}
