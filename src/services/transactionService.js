// Logika bisnis transaksi (Buy Now & penyelesaian).
import { db } from "../lib/db.js";

/**
 * Error bisnis dengan pesan ramah untuk ditampilkan ke user.
 * Handler menangkap ini dan menampilkannya secara ephemeral.
 */
export class BusinessError extends Error {}

/**
 * Eksekusi "Buy Now" terhadap sebuah listing.
 *
 * Untuk listing SELL: creator = seller, pengklik = buyer.
 * Untuk listing BUY : creator = buyer,  pengklik = seller (memenuhi permintaan).
 *
 * Atomik: listing "diklaim" dengan updateMany berfilter status ACTIVE,
 * sehingga dua orang yang klik bersamaan tidak bisa sama-sama berhasil.
 *
 * @param {object} input
 * @param {number} input.listingId
 * @param {string} input.actorId   Discord user ID yang menekan Buy Now.
 * @returns {Promise<{listing: object, transaction: object}>}
 * @throws {BusinessError}
 */
export async function buyNow({ listingId, actorId }) {
  return db.$transaction(async (tx) => {
    const listing = await tx.listing.findUnique({ where: { id: listingId } });

    if (!listing) throw new BusinessError("Listing tidak ditemukan.");
    if (listing.status !== "ACTIVE") {
      throw new BusinessError("Listing ini sudah tidak aktif.");
    }
    if (listing.creatorId === actorId) {
      throw new BusinessError("Kamu tidak bisa membeli listing-mu sendiri.");
    }

    // Klaim listing: hanya berhasil jika masih ACTIVE.
    const claimed = await tx.listing.updateMany({
      where: { id: listingId, status: "ACTIVE" },
      data: { status: "PENDING" },
    });
    if (claimed.count === 0) {
      // Orang lain mendahului di antara findUnique & updateMany.
      throw new BusinessError("Listing ini baru saja diambil orang lain.");
    }

    // Tentukan peran berdasarkan tipe listing.
    const isSell = listing.type === "SELL";
    const sellerId = isSell ? listing.creatorId : actorId;
    const buyerId = isSell ? actorId : listing.creatorId;

    const transaction = await tx.transaction.create({
      data: {
        listingId: listing.id,
        offerId: null, // Buy Now tidak melalui offer
        sellerId,
        buyerId,
        finalPrice: listing.price,
        status: "AGREED",
      },
    });

    const updatedListing = { ...listing, status: "PENDING" };
    return { listing: updatedListing, transaction };
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
