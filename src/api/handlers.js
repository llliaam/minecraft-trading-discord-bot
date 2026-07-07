// Handler tiap endpoint REST. Memanggil SERVICE yang sama dengan handler Discord
// (jangan akses Prisma langsung di sini — lihat CLAUDE.md).
//
// Konvensi: tiap handler menerima { body } dan mengembalikan { status, data }.
// `body` sudah di-parse jadi objek (atau {} bila kosong). Error bisnis dilempar
// sebagai BusinessError dan ditangkap oleh server jadi HTTP 400.
import {
  browseListings,
  createListing,
  attachMessageId,
  cancelListing,
  getMyListings,
} from "../services/listingService.js";
import { getUnclaimedMailbox, claimMailboxItem } from "../services/mailboxService.js";
import { redeemLinkCode, getLinkByMc } from "../services/linkService.js";
import {
  createOffer,
  getOffersForCreator,
  acceptOffer,
  rejectOffer,
} from "../services/offerService.js";
import { BusinessError, purchaseListing } from "../services/transactionService.js";
import { formatPrice, isValidKeyFormat, prettify } from "../lib/itemCatalog.js";
import { markOnline, markOffline, isOnline } from "../lib/onlinePlayers.js";
import {
  buildListingEmbed,
  buildListingButtons,
  buildOfferEmbed,
  buildOfferButtons,
} from "../lib/embeds.js";
import { getMarketplaceChannel, updateListingMessage } from "../lib/marketplace.js";
import { pushToMod } from "./ws.js";
import { getLinkByDiscord } from "../services/linkService.js";

/** GET /health — cek server hidup. Tidak butuh logika apa pun. */
export function health() {
  return { status: 200, data: { ok: true, service: "smpmarket-api" } };
}

/**
 * GET /listings — daftar listing ACTIVE untuk ditampilkan in-game.
 * Query opsional: ?type=SELL|BUY, ?page=1.
 * Mengembalikan bentuk ringkas yang gampang dipakai mod.
 */
export async function listings({ query }) {
  const type = query.type === "SELL" || query.type === "BUY" ? query.type : undefined;
  const page = Number(query.page) || 1;

  const result = await browseListings({ type, page });

  return {
    status: 200,
    data: {
      items: result.items.map((l) => ({
        id: l.id,
        type: l.type,
        itemKey: l.itemKey,
        itemLabel: l.itemLabel,
        quantity: l.quantity,
        priceItemKey: l.priceItemKey,
        priceQuantity: l.priceQuantity,
        // priceText: harga siap-tampil, biar mod tak perlu duplikasi katalog.
        priceText: formatPrice(l.priceQuantity, l.priceItemKey),
        description: l.description ?? null,
        creatorId: l.creatorId,
        creatorName: l.creatorName ?? null,
        status: l.status,
        reservedFor: l.reservedFor ?? null,
        reservedUntil: l.reservedUntil ? l.reservedUntil.toISOString() : null,
      })),
      page: result.page,
      totalPages: result.totalPages,
      total: result.total,
    },
  };
}

/**
 * POST /link/redeem — tukar kode linking dari in-game.
 * Body: { code, minecraftUuid, minecraftName }.
 * Dipanggil mod saat pemain ketik /link <kode> di server.
 */
export async function linkRedeem({ body }) {
  const { code, minecraftUuid, minecraftName } = body;

  if (!minecraftUuid || !minecraftName) {
    return {
      status: 400,
      data: { error: "minecraftUuid dan minecraftName wajib diisi." },
    };
  }

  // redeemLinkCode melempar BusinessError bila kode invalid/kedaluwarsa —
  // ditangkap server jadi HTTP 400 dengan pesannya.
  const { link } = await redeemLinkCode({ code, minecraftUuid, minecraftName });

  return {
    status: 200,
    data: {
      ok: true,
      discordId: link.discordId,
      minecraftName: link.minecraftName,
    },
  };
}

/** Resolve minecraftUuid → discordId, atau lempar BusinessError bila belum linked. */
async function resolveDiscordId(minecraftUuid) {
  const link = await getLinkByMc(minecraftUuid);
  if (!link) {
    throw new BusinessError("Akun Minecraft-mu belum tertaut. Jalankan /link di Discord dulu.");
  }
  return link.discordId;
}

/** Resolve discordId → minecraftUuid (null bila belum linked). */
async function resolveUuid(discordId) {
  const link = await getLinkByDiscord(discordId);
  return link?.minecraftUuid ?? null;
}

/**
 * POST /listings/buy — buat listing BUY (wishlist) dari in-game.
 * Body: { minecraftUuid, itemKey, quantity, priceItemKey, priceQuantity, description? }.
 */
export async function createBuyListing({ body, client }) {
  const { minecraftUuid, itemKey, quantity, priceItemKey, priceQuantity, description } = body;
  const creatorId = await resolveDiscordId(minecraftUuid);

  // Jalur in-game: item berasal dari registry MC asli. Cukup validasi FORMAT
  // (namespace:path) — semua item boleh, bukan hanya "barang berharga".
  if (!isValidKeyFormat(itemKey)) {
    throw new BusinessError(`Item "${itemKey}" tidak valid.`);
  }
  if (!isValidKeyFormat(priceItemKey)) {
    throw new BusinessError(`Item pembayaran "${priceItemKey}" tidak valid.`);
  }

  const listing = await createListing({
    creatorId,
    type: "BUY",
    itemKey,
    itemLabel: prettify(itemKey),
    quantity,
    priceItemKey,
    priceQuantity,
    description: description ?? null,
  });

  const channel = await getMarketplaceChannel(client);
  if (channel) {
    const message = await channel.send({
      embeds: [buildListingEmbed(listing)],
      components: buildListingButtons(listing),
    });
    await attachMessageId(listing.id, message.id);
  }

  return {
    status: 200,
    data: {
      id: listing.id,
      type: listing.type,
      itemKey: listing.itemKey,
      itemLabel: listing.itemLabel,
      quantity: listing.quantity,
      priceItemKey: listing.priceItemKey,
      priceQuantity: listing.priceQuantity,
      priceText: formatPrice(listing.priceQuantity, listing.priceItemKey),
    },
  };
}

/**
 * POST /listings/sell — buat listing SELL dari in-game (Fase E).
 * Body: { minecraftUuid, escrowRef, itemKey, quantity, priceItemKey, priceQuantity, description? }.
 *
 * Barang sudah disetor ke escrow oleh mod SEBELUM endpoint ini dipanggil;
 * `escrowRef` menautkan listing ke slot escrow fisiknya. Node tak pernah
 * menyentuh item — hanya menyimpan metadata + escrowRef (lihat CLAUDE.md).
 */
export async function createSellListing({ body, client }) {
  const { minecraftUuid, escrowRef, itemKey, quantity, priceItemKey, priceQuantity, description } =
    body;
  const creatorId = await resolveDiscordId(minecraftUuid);

  if (!escrowRef || typeof escrowRef !== "string") {
    throw new BusinessError("escrowRef wajib diisi untuk listing SELL.");
  }
  // Jalur in-game: item dari registry MC asli → validasi FORMAT saja.
  if (!isValidKeyFormat(itemKey)) {
    throw new BusinessError(`Item "${itemKey}" tidak valid.`);
  }
  if (!isValidKeyFormat(priceItemKey)) {
    throw new BusinessError(`Item pembayaran "${priceItemKey}" tidak valid.`);
  }

  const listing = await createListing({
    creatorId,
    type: "SELL",
    itemKey,
    itemLabel: prettify(itemKey),
    quantity,
    priceItemKey,
    priceQuantity,
    description: description ?? null,
    escrowRef,
  });

  const channel = await getMarketplaceChannel(client);
  if (channel) {
    const message = await channel.send({
      embeds: [buildListingEmbed(listing)],
      components: buildListingButtons(listing),
    });
    await attachMessageId(listing.id, message.id);
  }

  return {
    status: 200,
    data: {
      id: listing.id,
      type: listing.type,
      itemKey: listing.itemKey,
      itemLabel: listing.itemLabel,
      quantity: listing.quantity,
      priceItemKey: listing.priceItemKey,
      priceQuantity: listing.priceQuantity,
      priceText: formatPrice(listing.priceQuantity, listing.priceItemKey),
    },
  };
}

/**
 * POST /listings/cancel — batalkan listing SELL/BUY sendiri dari in-game.
 * Body: { minecraftUuid, listingId }.
 *
 * Pemain online (in-game) → mode "ingame": Node lepas metadata & kembalikan
 * `escrowRef` agar mod menarik barang fisik. Node tak menyentuh item.
 */
export async function cancelListingFromGame({ body, client }) {
  const { minecraftUuid, listingId } = body;
  const actorId = await resolveDiscordId(minecraftUuid);

  const { listing, escrowRef } = await cancelListing({
    listingId,
    actorId,
    returnMode: "ingame",
  });

  // Sinkronkan embed di channel → ❌ Dibatalkan, tanpa tombol.
  await updateListingMessage(client, listing);

  return { status: 200, data: { ok: true, listingId: listing.id, escrowRef } };
}

/**
 * POST /offers — buat offer baru dari in-game.
 * Body: { minecraftUuid, listingId, priceItemKey, priceQuantity, message? }.
 */
export async function createOfferFromGame({ body, client }) {
  const { minecraftUuid, listingId, priceItemKey, priceQuantity, message } = body;
  const buyerId = await resolveDiscordId(minecraftUuid);

  // Jalur in-game (item dari registry asli) → validasi format saja.
  if (!isValidKeyFormat(priceItemKey)) {
    throw new BusinessError(`Item pembayaran "${priceItemKey}" tidak valid.`);
  }

  const { offer, listing } = await createOffer({
    listingId,
    buyerId,
    priceItemKey,
    priceQuantity,
    message: message ?? null,
  });

  const channel = await getMarketplaceChannel(client);
  if (channel) {
    await channel
      .send({
        content: `<@${listing.creatorId}> kamu dapat offer baru!`,
        embeds: [buildOfferEmbed(offer, listing)],
        components: buildOfferButtons(offer),
      })
      .catch(() => {});
  }

  // Push notif in-game ke penjual (pemilik listing).
  const sellerUuid = await resolveUuid(listing.creatorId);
  if (sellerUuid) {
    pushToMod("OFFER_RECEIVED", {
      targetUuid: sellerUuid,
      listingId: listing.id,
      offerId: offer.id,
      itemLabel: listing.itemLabel,
      priceText: formatPrice(offer.priceQuantity, offer.priceItemKey),
    });
  }

  return {
    status: 200,
    data: {
      id: offer.id,
      listingId: offer.listingId,
      priceItemKey: offer.priceItemKey,
      priceQuantity: offer.priceQuantity,
      priceText: formatPrice(offer.priceQuantity, offer.priceItemKey),
      status: offer.status,
    },
  };
}

/**
 * GET /offers/mine — daftar offer PENDING masuk untuk listing milik pemain.
 * Query: ?minecraftUuid=.
 */
export async function myOffers({ query }) {
  const creatorId = await resolveDiscordId(query.minecraftUuid);
  const offers = await getOffersForCreator(creatorId);

  return {
    status: 200,
    data: {
      items: offers.map((o) => ({
        id: o.id,
        listingId: o.listingId,
        priceItemKey: o.priceItemKey,
        priceQuantity: o.priceQuantity,
        priceText: formatPrice(o.priceQuantity, o.priceItemKey),
        message: o.message,
        buyerId: o.buyerId,
        listing: {
          id: o.listing.id,
          itemKey: o.listing.itemKey,
          itemLabel: o.listing.itemLabel,
          quantity: o.listing.quantity,
          priceText: formatPrice(o.listing.priceQuantity, o.listing.priceItemKey),
        },
      })),
    },
  };
}

/**
 * GET /mailbox — daftar item mailbox yang belum diklaim milik pemain.
 * Query: ?minecraftUuid=.
 * Dipakai GUI `/myclaim` in-game untuk menampilkan daftar item menunggu.
 */
export async function mailboxList({ query }) {
  const ownerId = await resolveDiscordId(query.minecraftUuid);
  const items = await getUnclaimedMailbox(ownerId);

  return {
    status: 200,
    data: {
      items: items.map((m) => ({
        id: m.id,
        escrowRef: m.escrowRef,
        itemKey: m.itemKey,
        itemLabel: m.itemLabel,
        quantity: m.quantity,
        reason: m.reason,
        createdAt: m.createdAt.toISOString(),
      })),
    },
  };
}

/**
 * POST /mailbox/claim — klaim satu item mailbox dari in-game (Fase E3).
 * Body: { minecraftUuid, mailboxId }.
 *
 * Node menandai item sebagai diklaim (claimedAt=now) dan mengembalikan
 * `escrowRef` agar mod menarik stack fisik dari ledger → serahkan ke pemain.
 * Overflow (inventory penuh) ditangani mod: sisa masuk mailbox baru via
 * endpoint ini dengan item baru — atau mod pakai offerOrDrop (item jatuh
 * ke lantai jika inventory penuh). Node TAK menyentuh item.
 */
export async function mailboxClaim({ body }) {
  const { minecraftUuid, mailboxId } = body;

  if (!mailboxId || typeof mailboxId !== "number") {
    throw new BusinessError("mailboxId wajib diisi (integer).");
  }

  const ownerId = await resolveDiscordId(minecraftUuid);
  const { mailboxItem, escrowRef } = await claimMailboxItem({ mailboxId, ownerId });

  return {
    status: 200,
    data: {
      ok: true,
      mailboxId: mailboxItem.id,
      escrowRef,
      itemLabel: mailboxItem.itemLabel,
      quantity: mailboxItem.quantity,
    },
  };
}

/**
 * GET /listings/mine — listing ACTIVE & PENDING milik pemain (untuk GUI in-game).
 * Query: ?minecraftUuid=.
 */
export async function myListingsFromGame({ query }) {
  const creatorId = await resolveDiscordId(query.minecraftUuid);
  const items = await getMyListings(creatorId);

  return {
    status: 200,
    data: {
      items: items.map((l) => ({
        id: l.id,
        type: l.type,
        itemKey: l.itemKey,
        itemLabel: l.itemLabel,
        quantity: l.quantity,
        priceItemKey: l.priceItemKey,
        priceQuantity: l.priceQuantity,
        priceText: formatPrice(l.priceQuantity, l.priceItemKey),
        status: l.status,
        description: l.description ?? null,
        escrowRef: l.escrowRef ?? null,
        creatorName: l.creatorName ?? null,
      })),
    },
  };
}

/**
 * POST /listings/purchase — beli listing SELL dari in-game (Fase E2).
 * Body: { minecraftUuid, listingId, paymentEscrowRef }.
 *
 * Pembeli sudah menyetor pembayaran ke escrow SEBELUM endpoint ini dipanggil.
 * Node klaim listing ACTIVE→COMPLETED atomik, buat Transaction, buat MailboxItem
 * SOLD_PAYMENT untuk penjual, lalu kembalikan escrowRef barang-jual agar mod
 * menarik & menyerahkannya ke pembeli. Node TAK menyentuh item.
 */
export async function purchaseListingFromGame({ body, client }) {
  const { minecraftUuid, listingId, paymentEscrowRef } = body;

  if (!paymentEscrowRef || typeof paymentEscrowRef !== "string") {
    throw new BusinessError("paymentEscrowRef wajib diisi.");
  }

  const buyerId = await resolveDiscordId(minecraftUuid);

  const { listing, transaction, escrowRefToRelease } = await purchaseListing({
    listingId,
    buyerId,
    paymentEscrowRef,
  });

  // Sinkronkan embed di channel → ✅ Selesai, tanpa tombol.
  await updateListingMessage(client, listing);

  // Notifikasi channel.
  const channel = await getMarketplaceChannel(client);
  if (channel) {
    await channel
      .send({
        content:
          `✅ **Terjual!** <@${listing.creatorId}> — listing #${listing.id} ` +
          `(**${listing.itemLabel}** ×${listing.quantity}) dibeli oleh <@${buyerId}>. ` +
          `Pembayaran menunggu di mailbox penjual.`,
      })
      .catch(() => {});
  }

  // Push notif in-game ke penjual: barangnya terjual, pembayaran di mailbox.
  const sellerUuid = await resolveUuid(listing.creatorId);
  if (sellerUuid) {
    pushToMod("ITEM_SOLD", {
      targetUuid: sellerUuid,
      listingId: listing.id,
      itemLabel: listing.itemLabel,
      quantity: listing.quantity,
    });
  }

  return {
    status: 200,
    data: {
      ok: true,
      listingId: listing.id,
      transactionId: transaction.id,
      escrowRef: escrowRefToRelease,
    },
  };
}

/**
 * POST /offers/respond — accept/reject offer dari in-game.
 * Body: { minecraftUuid, offerId, decision } dengan decision = "accept" | "reject".
 */
export async function respondOffer({ body, client }) {
  const { minecraftUuid, offerId, decision } = body;
  const actorId = await resolveDiscordId(minecraftUuid);

  if (decision !== "accept" && decision !== "reject") {
    throw new BusinessError('decision harus "accept" atau "reject".');
  }

  if (decision === "accept") {
    const { listing, offer, transaction } = await acceptOffer({ offerId, actorId });

    const channel = await getMarketplaceChannel(client);
    if (channel) {
      await updateListingMessage(client, listing, { transactionId: transaction.id });
      await channel
        .send(`🤝 Deal! Listing #${listing.id} — offer #${offer.id} diterima.`)
        .catch(() => {});
    }

    // Push notif in-game ke pembeli: offer diterima, segera bayar.
    const buyerUuid = await resolveUuid(offer.buyerId);
    if (buyerUuid) {
      pushToMod("OFFER_ACCEPTED", {
        targetUuid: buyerUuid,
        listingId: listing.id,
        offerId: offer.id,
        itemLabel: listing.itemLabel,
      });
    }

    return { status: 200, data: { ok: true, offerId: offer.id, status: offer.status } };
  }

  const { offer } = await rejectOffer({ offerId, actorId });

  const channel = await getMarketplaceChannel(client);
  if (channel) {
    await channel
      .send(`<@${offer.buyerId}> maaf, offer #${offer.id}-mu ditolak.`)
      .catch(() => {});
  }

  // Push notif in-game ke pembeli: offer ditolak.
  const buyerUuid = await resolveUuid(offer.buyerId);
  if (buyerUuid) {
    pushToMod("OFFER_REJECTED", {
      targetUuid: buyerUuid,
      offerId: offer.id,
    });
  }

  return { status: 200, data: { ok: true, offerId: offer.id, status: offer.status } };
}

/**
 * POST /players/online — mod beritahu bot bahwa pemain join.
 * Body: { minecraftUuid }.
 */
export function playerJoin({ body }) {
  const { minecraftUuid } = body;
  if (!minecraftUuid) throw new BusinessError("minecraftUuid wajib diisi.");
  markOnline(minecraftUuid);
  return { status: 200, data: { ok: true } };
}

/**
 * DELETE /players/online — mod beritahu bot bahwa pemain quit.
 * Body: { minecraftUuid }.
 */
export function playerQuit({ body }) {
  const { minecraftUuid } = body;
  if (!minecraftUuid) throw new BusinessError("minecraftUuid wajib diisi.");
  markOffline(minecraftUuid);
  return { status: 200, data: { ok: true } };
}

/**
 * GET /players/online/:uuid — cek apakah pemain dengan UUID tertentu sedang online.
 * Dipakai Discord /cancel untuk memilih returnMode.
 */
export function playerOnlineCheck({ query }) {
  const { minecraftUuid } = query;
  if (!minecraftUuid) throw new BusinessError("minecraftUuid wajib diisi.");
  return { status: 200, data: { online: isOnline(minecraftUuid) } };
}
