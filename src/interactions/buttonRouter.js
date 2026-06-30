// Router semua interaksi tombol. Dipanggil dari events/interactionCreate.js.
import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  MessageFlags,
  ModalBuilder,
  TextInputBuilder,
  TextInputStyle,
} from "discord.js";
import { Action, buildId, parseId, decodeBrowse } from "../lib/ids.js";
import { browseListings, getListing } from "../services/listingService.js";
import {
  buyNow,
  completeTransaction,
  BusinessError,
} from "../services/transactionService.js";
import { acceptOffer, rejectOffer } from "../services/offerService.js";
import { buildListEmbed, buildBrowseButtons } from "../lib/embeds.js";
import { getMarketplaceChannel, updateListingMessage } from "../lib/marketplace.js";

/** Entry point: arahkan tombol ke handler sesuai prefix action. */
export async function handleButton(interaction) {
  const { customId } = interaction;

  if (customId.startsWith(`${Action.BROWSE_PAGE}:`)) {
    return handleBrowsePage(interaction);
  }

  const parsed = parseId(customId);
  if (!parsed) return;

  switch (parsed.action) {
    case Action.BUY_NOW:
      return handleBuyNowPrompt(interaction, parsed.entityId);
    case Action.BUY_CONFIRM:
      return handleBuyConfirm(interaction, parsed.entityId);
    case Action.MAKE_OFFER:
      return handleMakeOffer(interaction, parsed.entityId);
    case Action.OFFER_ACCEPT:
      return handleOfferAccept(interaction, parsed.entityId);
    case Action.OFFER_REJECT:
      return handleOfferReject(interaction, parsed.entityId);
    case Action.TX_COMPLETE:
      return handleMarkCompleted(interaction, parsed.entityId);
    default:
      return;
  }
}

// ===== Make Offer: tampilkan modal input =====
async function handleMakeOffer(interaction, listingId) {
  const listing = await getListing(listingId);

  if (!listing || listing.status !== "ACTIVE") {
    await interaction.reply({
      content: "⚠️ Listing ini sudah tidak menerima offer.",
      flags: MessageFlags.Ephemeral,
    });
    return;
  }
  if (listing.creatorId === interaction.user.id) {
    await interaction.reply({
      content: "⚠️ Kamu tidak bisa menawar listing-mu sendiri.",
      flags: MessageFlags.Ephemeral,
    });
    return;
  }

  const modal = new ModalBuilder()
    .setCustomId(buildId(Action.OFFER_MODAL, listing.id))
    .setTitle(`Offer untuk ${listing.itemName}`.slice(0, 45));

  const priceInput = new TextInputBuilder()
    .setCustomId("offeredPrice")
    .setLabel("Tawaranmu")
    .setPlaceholder('mis. "50 diamond" atau "1 stack emerald"')
    .setStyle(TextInputStyle.Short)
    .setMaxLength(100)
    .setRequired(true);

  const messageInput = new TextInputBuilder()
    .setCustomId("message")
    .setLabel("Pesan (opsional)")
    .setStyle(TextInputStyle.Paragraph)
    .setMaxLength(500)
    .setRequired(false);

  modal.addComponents(
    new ActionRowBuilder().addComponents(priceInput),
    new ActionRowBuilder().addComponents(messageInput),
  );

  await interaction.showModal(modal);
}

// ===== Pagination /browse =====
async function handleBrowsePage(interaction) {
  await interaction.deferUpdate();
  const decoded = decodeBrowse(interaction.customId);
  if (!decoded) return;
  const page = await browseListings({ type: decoded.type, page: decoded.page });
  await interaction.editReply({
    embeds: [buildListEmbed(page, { type: decoded.type })],
    components: buildBrowseButtons(page, decoded.type),
  });
}

// ===== Buy Now: tahap 1 — tampilkan konfirmasi (ephemeral) =====
async function handleBuyNowPrompt(interaction, listingId) {
  await interaction.deferReply({ ephemeral: true });
  const listing = await getListing(listingId);

  if (!listing || listing.status !== "ACTIVE") {
    await interaction.editReply({ content: "⚠️ Listing ini sudah tidak aktif." });
    return;
  }
  if (listing.creatorId === interaction.user.id) {
    await interaction.editReply({ content: "⚠️ Kamu tidak bisa membeli listing-mu sendiri." });
    return;
  }

  const isSell = listing.type === "SELL";
  const verb = isSell ? "membeli" : "memenuhi permintaan";
  const confirmRow = new ActionRowBuilder().addComponents(
    new ButtonBuilder()
      .setCustomId(buildId(Action.BUY_CONFIRM, listing.id))
      .setLabel("Ya, lanjutkan")
      .setEmoji("✅")
      .setStyle(ButtonStyle.Success),
  );

  await interaction.editReply({
    content:
      `Kamu akan ${verb} **${listing.itemName}** ×${listing.quantity} ` +
      `seharga **${listing.price}**.\nLanjutkan?`,
    components: [confirmRow],
  });
}

// ===== Buy Now: tahap 2 — eksekusi setelah konfirmasi =====
async function handleBuyConfirm(interaction, listingId) {
  await interaction.deferUpdate();
  let result;
  try {
    result = await buyNow({ listingId, actorId: interaction.user.id });
  } catch (err) {
    if (err instanceof BusinessError) {
      await interaction.editReply({ content: `⚠️ ${err.message}`, components: [] });
      return;
    }
    throw err;
  }

  const { listing, transaction } = result;

  // Update embed listing di channel → PENDING + tombol Mark Completed.
  await updateListingMessage(interaction.client, listing, {
    transactionId: transaction.id,
  });

  // Ping kedua pihak di channel marketplace.
  const channel = await getMarketplaceChannel(interaction.client);
  if (channel) {
    await channel
      .send({
        content:
          `🤝 **Deal!** <@${transaction.sellerId}> & <@${transaction.buyerId}> ` +
          `untuk **${listing.itemName}** ×${listing.quantity} (${listing.price}).\n` +
          `Silakan koordinasi trade in-game. Klik **Tandai Selesai** di listing #${listing.id} bila sudah beres.`,
      })
      .catch(() => {});
  }

  await interaction.editReply({
    content: `✅ Deal tercatat untuk listing #${listing.id}. Cek channel marketplace.`,
    components: [],
  });
}

// ===== Accept offer =====
async function handleOfferAccept(interaction, offerId) {
  await interaction.deferUpdate();
  let result;
  try {
    result = await acceptOffer({ offerId, actorId: interaction.user.id });
  } catch (err) {
    if (err instanceof BusinessError) {
      await interaction.followUp({ content: `⚠️ ${err.message}`, ephemeral: true });
      return;
    }
    throw err;
  }

  const { listing, transaction } = result;

  // Embed listing di channel → PENDING + tombol Mark Completed.
  await updateListingMessage(interaction.client, listing, { transactionId: transaction.id });

  // Nonaktifkan tombol di pesan offer & tandai diterima.
  await interaction.editReply({
    content: `✅ Offer #${offerId} diterima.`,
    embeds: interaction.message.embeds,
    components: [],
  });

  // Ping kedua pihak.
  const channel = await getMarketplaceChannel(interaction.client);
  if (channel) {
    await channel
      .send({
        content:
          `🤝 **Deal!** <@${transaction.sellerId}> & <@${transaction.buyerId}> ` +
          `untuk **${listing.itemName}** ×${listing.quantity} (${transaction.finalPrice}).\n` +
          `Silakan koordinasi trade in-game. Klik **Tandai Selesai** di listing #${listing.id} bila sudah beres.`,
      })
      .catch(() => {});
  }
}

// ===== Reject offer =====
async function handleOfferReject(interaction, offerId) {
  await interaction.deferUpdate();
  let result;
  try {
    result = await rejectOffer({ offerId, actorId: interaction.user.id });
  } catch (err) {
    if (err instanceof BusinessError) {
      await interaction.followUp({ content: `⚠️ ${err.message}`, ephemeral: true });
      return;
    }
    throw err;
  }

  const { offer } = result;

  // Nonaktifkan tombol di pesan offer & tandai ditolak.
  await interaction.editReply({
    content: `❌ Offer #${offerId} ditolak.`,
    embeds: interaction.message.embeds,
    components: [],
  });

  // Beri tahu pembuat offer.
  const channel = await getMarketplaceChannel(interaction.client);
  if (channel) {
    await channel
      .send({ content: `<@${offer.buyerId}> maaf, offer #${offer.id}-mu ditolak.` })
      .catch(() => {});
  }
}

// ===== Mark Completed =====
async function handleMarkCompleted(interaction, transactionId) {
  await interaction.deferReply({ ephemeral: true });
  let result;
  try {
    result = await completeTransaction({ transactionId, actorId: interaction.user.id });
  } catch (err) {
    if (err instanceof BusinessError) {
      await interaction.editReply({ content: `⚠️ ${err.message}` });
      return;
    }
    throw err;
  }

  const { listing, transaction } = result;

  // Embed listing → COMPLETED (biru "Selesai"), tanpa tombol.
  await updateListingMessage(interaction.client, listing);

  await interaction.editReply({
    content:
      `✅ Listing #${listing.id} ditandai **selesai**. ` +
      `Terima kasih, <@${transaction.sellerId}> & <@${transaction.buyerId}>!`,
  });

  const channel = await getMarketplaceChannel(interaction.client);
  if (channel) {
    await channel
      .send({
        content:
          `📦 Transaksi **${listing.itemName}** ×${listing.quantity} selesai ` +
          `antara <@${transaction.sellerId}> & <@${transaction.buyerId}>.`,
      })
      .catch(() => {});
  }
}
