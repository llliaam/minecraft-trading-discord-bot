// /link — mulai proses menautkan akun Discord ke Minecraft.
// Bot mengeluarkan kode; pemain mengetik kode itu di dalam game (mod, Fase C).
import { SlashCommandBuilder, MessageFlags } from "discord.js";
import { generateLinkCode } from "../services/linkService.js";
import { BusinessError } from "../services/transactionService.js";

export default {
  data: new SlashCommandBuilder()
    .setName("link")
    .setDescription("Tautkan akun Discord-mu ke akun Minecraft"),

  async execute(interaction) {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });

    let result;
    try {
      result = await generateLinkCode(interaction.user.id);
    } catch (err) {
      if (err instanceof BusinessError) {
        await interaction.editReply({ content: `⚠️ ${err.message}` });
        return;
      }
      throw err;
    }

    const { code, expiresAt } = result;
    const expiryUnix = Math.floor(expiresAt.getTime() / 1000);

    await interaction.editReply({
      content:
        `🔗 **Kode tautan kamu: \`${code}\`**\n\n` +
        `Masuk ke server Minecraft, lalu ketik:\n` +
        `\`\`\`\n/link ${code}\n\`\`\`\n` +
        `Kode berlaku sampai <t:${expiryUnix}:R> (sekali pakai).`,
    });
  },
};
