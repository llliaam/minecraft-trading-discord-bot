// /mylistings — lihat listing sendiri yang masih aktif/berjalan (ephemeral).
import { SlashCommandBuilder, MessageFlags } from "discord.js";
import { getMyListings } from "../services/listingService.js";
import { buildMyListingsEmbed } from "../lib/embeds.js";

export default {
  data: new SlashCommandBuilder()
    .setName("mylistings")
    .setDescription("Lihat listing milikmu yang sedang aktif atau menunggu trade"),

  async execute(interaction) {
    await interaction.deferReply({ ephemeral: true });
    const items = await getMyListings(interaction.user.id);

    await interaction.editReply({
      embeds: [buildMyListingsEmbed(items)],
    });
  },
};
