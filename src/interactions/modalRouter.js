// Router semua submit modal. Dipanggil dari events/interactionCreate.js.
import { MessageFlags } from "discord.js";
import { Action, parseId } from "../lib/ids.js";
import { createOffer } from "../services/offerService.js";
import { BusinessError } from "../services/transactionService.js";
import { buildOfferEmbed, buildOfferButtons } from "../lib/embeds.js";
import { getMarketplaceChannel } from "../lib/marketplace.js";

/** Entry point: arahkan submit modal ke handler sesuai prefix action. */
export async function handleModal(interaction) {
  const parsed = parseId(interaction.customId);
  if (!parsed) return;

  switch (parsed.action) {
    case Action.OFFER_MODAL:
      return handleOfferSubmit(interaction, parsed.entityId);
    default:
      return;
  }
}

async function handleOfferSubmit(interaction, listingId) {
  const offeredPrice = interaction.fields.getTextInputValue("offeredPrice").trim();
  const message = interaction.fields.getTextInputValue("message")?.trim() || null;

  if (!offeredPrice) {
    await interaction.reply({
      content: "⚠️ Tawaran tidak boleh kosong.",
      flags: MessageFlags.Ephemeral,
    });
    return;
  }

  let result;
  try {
    result = await createOffer({
      listingId,
      buyerId: interaction.user.id,
      offeredPrice,
      message,
    });
  } catch (err) {
    if (err instanceof BusinessError) {
      await interaction.reply({
        content: `⚠️ ${err.message}`,
        flags: MessageFlags.Ephemeral,
      });
      return;
    }
    throw err;
  }

  const { offer, listing } = result;

  // Ping creator listing di channel marketplace dengan tombol Accept/Reject.
  const channel = await getMarketplaceChannel(interaction.client);
  if (channel) {
    await channel
      .send({
        content: `<@${listing.creatorId}> kamu dapat offer baru!`,
        embeds: [buildOfferEmbed(offer, listing)],
        components: buildOfferButtons(offer),
      })
      .catch(() => {});
  }

  await interaction.reply({
    content: `✅ Offer **#${offer.id}** terkirim ke <@${listing.creatorId}>.`,
    flags: MessageFlags.Ephemeral,
  });
}
