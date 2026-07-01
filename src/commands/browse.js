// /browse — lihat listing aktif (paginated, ephemeral).
import { SlashCommandBuilder, MessageFlags } from "discord.js";
import { browseListings } from "../services/listingService.js";
import { buildListEmbed, buildBrowseButtons } from "../lib/embeds.js";

export default {
  data: new SlashCommandBuilder()
    .setName("browse")
    .setDescription("Lihat listing yang sedang aktif di marketplace")
    .addStringOption((o) =>
      o
        .setName("type")
        .setDescription("Saring berdasarkan tipe")
        .addChoices(
          { name: "Jual (SELL)", value: "SELL" },
          { name: "Cari (BUY)", value: "BUY" },
        ),
    ),

  async execute(interaction) {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const type = interaction.options.getString("type") ?? undefined;
    const page = await browseListings({ type, page: 1 });

    await interaction.editReply({
      embeds: [buildListEmbed(page, { type })],
      components: buildBrowseButtons(page, type),
    });
  },
};
