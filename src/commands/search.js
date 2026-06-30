// /search — cari listing aktif berdasarkan nama item.
import { SlashCommandBuilder, MessageFlags } from "discord.js";
import { searchListings } from "../services/listingService.js";
import { buildListEmbed } from "../lib/embeds.js";

const MAX_RESULTS = 10;

export default {
  data: new SlashCommandBuilder()
    .setName("search")
    .setDescription("Cari listing berdasarkan nama item")
    .addStringOption((o) =>
      o
        .setName("item")
        .setDescription("Kata kunci nama item")
        .setRequired(true)
        .setMaxLength(100),
    )
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
    await interaction.deferReply({ ephemeral: true });
    const query = interaction.options.getString("item", true).trim();
    const type = interaction.options.getString("type") ?? undefined;

    const result = await searchListings({ query, type, limit: MAX_RESULTS });

    const title =
      result.total > MAX_RESULTS
        ? `🔍 Hasil "${query}" (${MAX_RESULTS} dari ${result.total})`
        : `🔍 Hasil "${query}" (${result.total})`;

    await interaction.editReply({
      embeds: [buildListEmbed(result, { type, title })],
    });
  },
};
