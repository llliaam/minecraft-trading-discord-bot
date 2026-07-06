// Builder tampilan: embed kartu listing + baris tombol aksi.
import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  EmbedBuilder,
} from "discord.js";
import { Action, buildId, encodeBrowse } from "./ids.js"; // Action.MAKE_OFFER, TX_COMPLETE, BROWSE_PAGE
import { formatPrice } from "./itemCatalog.js";

// Warna embed per status (hex → angka).
const STATUS_COLOR = {
  ACTIVE: 0x57f287, // hijau
  RESERVED: 0xeb459e, // pink — terkunci utk 1 pembeli (Fase E)
  PENDING: 0xfee75c, // kuning — ada deal, menunggu trade
  COMPLETED: 0x5865f2, // biru — selesai
  CANCELLED: 0xed4245, // merah
  EXPIRED: 0x99aab5, // abu-abu
};

// Label & emoji status untuk judul embed.
const STATUS_BADGE = {
  ACTIVE: "🟢 Tersedia",
  RESERVED: "🔒 Direservasi",
  PENDING: "🟡 Menunggu Trade",
  COMPLETED: "✅ Selesai",
  CANCELLED: "❌ Dibatalkan",
  EXPIRED: "⏳ Kadaluarsa",
};

/**
 * Bangun embed untuk sebuah listing.
 * @param {object} listing - record Listing dari Prisma.
 */
export function buildListingEmbed(listing) {
  const isSell = listing.type === "SELL";
  const typeLabel = isSell ? "🛒 MENJUAL" : "🔎 MENCARI";

  const embed = new EmbedBuilder()
    .setColor(STATUS_COLOR[listing.status] ?? 0x2b2d31)
    .setTitle(`${typeLabel} — ${listing.itemLabel}`)
    .addFields(
      { name: "Jumlah", value: String(listing.quantity), inline: true },
      {
        name: "Harga",
        value: formatPrice(listing.priceQuantity, listing.priceItemKey),
        inline: true,
      },
      { name: "Status", value: STATUS_BADGE[listing.status] ?? listing.status, inline: true },
      {
        name: "Oleh",
        value: listing.creatorName
          ? `${listing.creatorName} (<@${listing.creatorId}>)`
          : `<@${listing.creatorId}>`,
        inline: true,
      },
    )
    .setFooter({ text: `Listing #${listing.id}` })
    .setTimestamp(listing.createdAt ?? new Date());

  if (listing.description) {
    embed.setDescription(listing.description);
  }

  return embed;
}

/**
 * Baris tombol aksi untuk listing.
 * - ACTIVE  → Make Offer (beli = in-game only via escrow, Fase E2).
 * - PENDING → Mark Completed (jika transactionId diberikan).
 * - lainnya → tidak ada tombol.
 * @param {object} listing
 * @param {object} [opts]
 * @param {number} [opts.transactionId]  untuk tombol Mark Completed saat PENDING.
 */
export function buildListingButtons(listing, { transactionId } = {}) {
  if (listing.status === "ACTIVE") {
    const row = new ActionRowBuilder().addComponents(
      new ButtonBuilder()
        .setCustomId(buildId(Action.MAKE_OFFER, listing.id))
        .setLabel("Make Offer")
        .setEmoji("💬")
        .setStyle(ButtonStyle.Primary),
    );
    return [row];
  }

  if (listing.status === "PENDING" && transactionId != null) {
    const row = new ActionRowBuilder().addComponents(
      new ButtonBuilder()
        .setCustomId(buildId(Action.TX_COMPLETE, transactionId))
        .setLabel("Tandai Selesai")
        .setEmoji("✔️")
        .setStyle(ButtonStyle.Success),
    );
    return [row];
  }

  return [];
}

// ===== Tampilan /browse & /search (daftar ringkas) =====

/** Satu baris ringkas untuk daftar listing. */
function formatListingLine(listing) {
  const typeIcon = listing.type === "SELL" ? "🛒" : "🔎";
  const desc = listing.description ? ` — ${listing.description}` : "";
  const price = formatPrice(listing.priceQuantity, listing.priceItemKey);
  const byLabel = listing.creatorName
    ? `${listing.creatorName} (<@${listing.creatorId}>)`
    : `<@${listing.creatorId}>`;
  return (
    `${typeIcon} **#${listing.id}** · **${listing.itemLabel}** ×${listing.quantity}\n` +
    `   💰 ${price} · oleh ${byLabel}${desc}`
  );
}

/**
 * Embed daftar listing (paginated).
 * @param {object} page  hasil browseListings/searchListings.
 * @param {object} [opts]
 * @param {"SELL"|"BUY"} [opts.type]  filter aktif (untuk judul).
 * @param {string} [opts.title]       judul kustom (mis. hasil pencarian).
 */
export function buildListEmbed(page, { type, title } = {}) {
  const typeLabel = type === "SELL" ? " (Jual)" : type === "BUY" ? " (Cari)" : "";
  const embed = new EmbedBuilder()
    .setColor(0x2b2d31)
    .setTitle(title ?? `📋 Marketplace${typeLabel}`);

  if (page.items.length === 0) {
    embed.setDescription("_Tidak ada listing yang cocok._");
  } else {
    embed.setDescription(page.items.map(formatListingLine).join("\n\n"));
    embed.setFooter({
      text: `Halaman ${page.page}/${page.totalPages} · ${page.total} listing aktif`,
    });
  }

  return embed;
}

/**
 * Tombol Prev/Next untuk /browse. Disabled di ujung halaman.
 * Tidak dipakai oleh /search (hasil pencarian ditampilkan satu halaman).
 * @param {object} page  hasil browseListings.
 * @param {"SELL"|"BUY"} [type]
 */
export function buildBrowseButtons(page, type) {
  if (page.totalPages <= 1) return [];

  const row = new ActionRowBuilder().addComponents(
    new ButtonBuilder()
      .setCustomId(encodeBrowse(type, page.page - 1))
      .setLabel("Sebelumnya")
      .setEmoji("◀️")
      .setStyle(ButtonStyle.Secondary)
      .setDisabled(page.page <= 1),
    new ButtonBuilder()
      .setCustomId(encodeBrowse(type, page.page + 1))
      .setLabel("Berikutnya")
      .setEmoji("▶️")
      .setStyle(ButtonStyle.Secondary)
      .setDisabled(page.page >= page.totalPages),
  );

  return [row];
}

// ===== Tampilan /mylistings =====

/** Satu baris untuk daftar listing milik sendiri (menyertakan status). */
function formatMyListingLine(listing) {
  const typeIcon = listing.type === "SELL" ? "🛒" : "🔎";
  const badge = STATUS_BADGE[listing.status] ?? listing.status;
  const price = formatPrice(listing.priceQuantity, listing.priceItemKey);
  return (
    `${typeIcon} **#${listing.id}** · **${listing.itemLabel}** ×${listing.quantity} — ${badge}\n` +
    `   💰 ${price}`
  );
}

/**
 * Embed daftar listing milik user sendiri (ACTIVE & PENDING).
 * @param {object[]} items  daftar record Listing.
 */
export function buildMyListingsEmbed(items) {
  const embed = new EmbedBuilder()
    .setColor(0x2b2d31)
    .setTitle("📦 Listing-mu");

  if (items.length === 0) {
    embed.setDescription(
      "_Kamu belum punya listing aktif._ Buat dengan `/sell` atau `/buy`.",
    );
  } else {
    embed.setDescription(items.map(formatMyListingLine).join("\n\n"));
    embed.setFooter({
      text: `${items.length} listing · batalkan dengan /cancel id:<nomor>`,
    });
  }

  return embed;
}

// ===== Tampilan Offer =====

/**
 * Embed notifikasi offer baru (dikirim ke channel, ping creator listing).
 * @param {object} offer    record Offer.
 * @param {object} listing  record Listing terkait.
 */
export function buildOfferEmbed(offer, listing) {
  const listingPrice = formatPrice(listing.priceQuantity, listing.priceItemKey);
  const offerPrice = formatPrice(offer.priceQuantity, offer.priceItemKey);
  const embed = new EmbedBuilder()
    .setColor(0xeb459e) // pink — butuh perhatian
    .setTitle(`💬 Offer baru untuk ${listing.itemLabel}`)
    .addFields(
      { name: "Listing", value: `#${listing.id} (${listingPrice})`, inline: true },
      { name: "Tawaran", value: offerPrice, inline: true },
      { name: "Dari", value: `<@${offer.buyerId}>`, inline: true },
    )
    .setFooter({ text: `Offer #${offer.id}` })
    .setTimestamp(offer.createdAt ?? new Date());

  if (offer.message) embed.setDescription(offer.message);
  return embed;
}

/** Tombol Accept/Reject untuk sebuah offer (key: offerId). */
export function buildOfferButtons(offer) {
  const row = new ActionRowBuilder().addComponents(
    new ButtonBuilder()
      .setCustomId(buildId(Action.OFFER_ACCEPT, offer.id))
      .setLabel("Terima")
      .setEmoji("✅")
      .setStyle(ButtonStyle.Success),
    new ButtonBuilder()
      .setCustomId(buildId(Action.OFFER_REJECT, offer.id))
      .setLabel("Tolak")
      .setEmoji("❌")
      .setStyle(ButtonStyle.Danger),
  );
  return [row];
}
