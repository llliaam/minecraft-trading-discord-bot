// Logika bisnis untuk Offer.
import { db } from "../lib/db.js";
import { BusinessError } from "./transactionService.js";

/**
 * Buat offer baru terhadap sebuah listing.
 * @param {object} input
 * @param {number} input.listingId
 * @param {string} input.buyerId         Discord user ID pembuat offer.
 * @param {string} input.priceItemKey    usulan item bayar, mis. "minecraft:diamond".
 * @param {number} input.priceQuantity   usulan jumlah.
 * @param {string|null} [input.message]
 * @returns {Promise<{offer: object, listing: object}>}
 * @throws {BusinessError}
 */
export async function createOffer({ listingId, buyerId, priceItemKey, priceQuantity, message }) {
  const listing = await db.listing.findUnique({ where: { id: listingId } });

  if (!listing) throw new BusinessError("Listing tidak ditemukan.");
  if (listing.status === "RESERVED") {
    throw new BusinessError(
      "Listing ini sedang direservasi untuk pembeli lain. Tunggu 24 jam bila reservasi tidak diselesaikan.",
    );
  }
  if (listing.status !== "ACTIVE") {
    throw new BusinessError("Listing ini sudah tidak menerima offer.");
  }
  if (listing.creatorId === buyerId) {
    throw new BusinessError("Kamu tidak bisa menawar listing-mu sendiri.");
  }

  const offer = await db.offer.create({
    data: {
      listingId,
      buyerId,
      priceItemKey,
      priceQuantity,
      message: message ?? null,
      status: "PENDING",
    },
  });

  return { offer, listing };
}

/** Ambil semua offer PENDING pada listing milik creatorId (utk /myoffers in-game). */
export function getOffersForCreator(creatorId) {
  return db.offer.findMany({
    where: { status: "PENDING", listing: { creatorId } },
    include: { listing: true },
    orderBy: { createdAt: "desc" },
  });
}

/** Ambil satu offer beserta listing-nya (atau null). */
export function getOfferWithListing(offerId) {
  return db.offer.findUnique({
    where: { id: offerId },
    include: { listing: true },
  });
}

/**
 * Terima offer. Hanya creator listing yang boleh.
 * Atomik: klaim listing (ACTIVE→PENDING), offer ini ACCEPTED, buat Transaction,
 * dan tolak otomatis semua offer PENDING lain di listing yang sama.
 *
 * @param {object} input
 * @param {number} input.offerId
 * @param {string} input.actorId   Discord user ID penekan tombol.
 * @returns {Promise<{listing: object, offer: object, transaction: object, rejectedOfferIds: number[]}>}
 * @throws {BusinessError}
 */
export async function acceptOffer({ offerId, actorId }) {
  return db.$transaction(async (tx) => {
    const offer = await tx.offer.findUnique({
      where: { id: offerId },
      include: { listing: true },
    });

    if (!offer) throw new BusinessError("Offer tidak ditemukan.");
    const listing = offer.listing;
    if (listing.creatorId !== actorId) {
      throw new BusinessError("Hanya pemilik listing yang bisa menerima offer ini.");
    }
    if (offer.status !== "PENDING") {
      throw new BusinessError("Offer ini sudah tidak menunggu respons.");
    }
    if (listing.status !== "ACTIVE") {
      throw new BusinessError("Listing ini sudah tidak aktif.");
    }

    // Klaim listing: hanya berhasil jika masih ACTIVE (anti balapan dgn Buy Now/accept lain).
    const reservedUntil = new Date(Date.now() + 24 * 60 * 60 * 1000); // +24 jam
    const isSellListing = listing.type === "SELL";
    // Untuk SELL: listing → RESERVED (pembeli wajib setor in-game dalam 24 jam).
    // Untuk BUY: tidak ada escrow item — langsung PENDING (penjual setor di sesi trade).
    const nextStatus = isSellListing ? "RESERVED" : "PENDING";
    const reservationData = isSellListing
      ? { reservedFor: offer.buyerId, reservedUntil }
      : {};

    const claimed = await tx.listing.updateMany({
      where: { id: listing.id, status: "ACTIVE" },
      data: { status: nextStatus, ...reservationData },
    });
    if (claimed.count === 0) {
      throw new BusinessError("Listing ini baru saja berubah status.");
    }

    await tx.offer.update({ where: { id: offer.id }, data: { status: "ACCEPTED" } });

    // Tolak otomatis offer PENDING lain di listing yang sama.
    const others = await tx.offer.findMany({
      where: { listingId: listing.id, status: "PENDING", id: { not: offer.id } },
      select: { id: true },
    });
    const rejectedOfferIds = others.map((o) => o.id);
    if (rejectedOfferIds.length > 0) {
      await tx.offer.updateMany({
        where: { id: { in: rejectedOfferIds } },
        data: { status: "REJECTED" },
      });
    }

    // Peran: listing SELL → creator seller, pembuat offer buyer. BUY → kebalikannya.
    const isSell = listing.type === "SELL";
    const sellerId = isSell ? listing.creatorId : offer.buyerId;
    const buyerId = isSell ? offer.buyerId : listing.creatorId;

    const transaction = await tx.transaction.create({
      data: {
        listingId: listing.id,
        offerId: offer.id,
        sellerId,
        buyerId,
        finalItemKey: offer.priceItemKey,
        finalQuantity: offer.priceQuantity,
        status: "AGREED",
      },
    });

    const updatedListing = {
      ...listing,
      status: nextStatus,
      ...(isSellListing ? { reservedFor: offer.buyerId, reservedUntil } : {}),
    };

    return {
      listing: updatedListing,
      offer: { ...offer, status: "ACCEPTED" },
      transaction,
      rejectedOfferIds,
    };
  });
}

/**
 * Tolak satu offer. Hanya creator listing yang boleh.
 * @param {object} input
 * @param {number} input.offerId
 * @param {string} input.actorId
 * @returns {Promise<{offer: object, listing: object}>}
 * @throws {BusinessError}
 */
export async function rejectOffer({ offerId, actorId }) {
  const offer = await db.offer.findUnique({
    where: { id: offerId },
    include: { listing: true },
  });

  if (!offer) throw new BusinessError("Offer tidak ditemukan.");
  if (offer.listing.creatorId !== actorId) {
    throw new BusinessError("Hanya pemilik listing yang bisa menolak offer ini.");
  }
  if (offer.status !== "PENDING") {
    throw new BusinessError("Offer ini sudah tidak menunggu respons.");
  }

  await db.offer.update({ where: { id: offer.id }, data: { status: "REJECTED" } });
  return { offer: { ...offer, status: "REJECTED" }, listing: offer.listing };
}
