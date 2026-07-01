// /cancel — batalkan listing sendiri yang masih ACTIVE.
import { SlashCommandBuilder, MessageFlags } from "discord.js";
import { cancelListing } from "../services/listingService.js";
import { BusinessError } from "../services/transactionService.js";
import { updateListingMessage } from "../lib/marketplace.js";

export default {
  data: new SlashCommandBuilder()
    .setName("cancel")
    .setDescription("Batalkan listing milikmu")
    .addIntegerOption((o) =>
      o
        .setName("id")
        .setDescription("Nomor listing (lihat /mylistings)")
        .setRequired(true)
        .setMinValue(1),
    ),

  async execute(interaction) {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const listingId = interaction.options.getInteger("id", true);

    let result;
    try {
      result = await cancelListing({ listingId, actorId: interaction.user.id });
    } catch (err) {
      if (err instanceof BusinessError) {
        await interaction.editReply({ content: `⚠️ ${err.message}` });
        return;
      }
      throw err;
    }

    const { listing } = result;

    // Update embed di channel marketplace → ❌ Dibatalkan, tanpa tombol.
    await updateListingMessage(interaction.client, listing);

    await interaction.editReply({
      content: `✅ Listing **#${listing.id}** (${listing.itemLabel}) dibatalkan.`,
    });
  },
};
