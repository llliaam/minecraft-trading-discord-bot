// /whoami — cek status tautan akun Discord <-> Minecraft.
import { SlashCommandBuilder, MessageFlags } from "discord.js";
import { getLinkByDiscord } from "../services/linkService.js";

export default {
  data: new SlashCommandBuilder()
    .setName("whoami")
    .setDescription("Lihat akun Minecraft yang tertaut ke Discord-mu"),

  async execute(interaction) {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });

    const link = await getLinkByDiscord(interaction.user.id);

    if (!link) {
      await interaction.editReply({
        content: "❌ Akun-mu belum tertaut. Mulai dengan `/link`.",
      });
      return;
    }

    const linkedUnix = Math.floor(link.linkedAt.getTime() / 1000);
    await interaction.editReply({
      content:
        `✅ Tertaut ke Minecraft **${link.minecraftName}**\n` +
        `Sejak <t:${linkedUnix}:D>.`,
    });
  },
};
