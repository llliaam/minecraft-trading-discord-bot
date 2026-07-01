// /unlink — putuskan tautan akun Discord <-> Minecraft.
import { SlashCommandBuilder, MessageFlags } from "discord.js";
import { unlink } from "../services/linkService.js";
import { BusinessError } from "../services/transactionService.js";

export default {
  data: new SlashCommandBuilder()
    .setName("unlink")
    .setDescription("Putuskan tautan akun Discord-mu dari Minecraft"),

  async execute(interaction) {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });

    let result;
    try {
      result = await unlink(interaction.user.id);
    } catch (err) {
      if (err instanceof BusinessError) {
        await interaction.editReply({ content: `⚠️ ${err.message}` });
        return;
      }
      throw err;
    }

    await interaction.editReply({
      content: `✅ Tautan ke Minecraft **${result.link.minecraftName}** diputus.`,
    });
  },
};
