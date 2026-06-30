// Router utama semua interaksi: slash command, tombol, modal, select menu.
import { Events, MessageFlags } from "discord.js";
import { handleButton } from "../interactions/buttonRouter.js";
import { handleModal } from "../interactions/modalRouter.js";

export default {
  name: Events.InteractionCreate,
  async execute(interaction) {
    try {
      if (interaction.isChatInputCommand()) {
        const command = interaction.client.commands.get(interaction.commandName);
        if (!command) {
          console.warn(`⚠️  Command tidak dikenal: ${interaction.commandName}`);
          return;
        }
        await command.execute(interaction);
        return;
      }

      if (interaction.isButton()) {
        await handleButton(interaction);
        return;
      }

      if (interaction.isModalSubmit()) {
        await handleModal(interaction);
        return;
      }
    } catch (err) {
      console.error("❌ Error saat menangani interaksi:", err);
      const reply = {
        content: "⚠️ Terjadi kesalahan saat memproses permintaanmu.",
        flags: MessageFlags.Ephemeral,
      };
      if (interaction.deferred || interaction.replied) {
        await interaction.followUp(reply).catch(() => {});
      } else {
        await interaction.reply(reply).catch(() => {});
      }
    }
  },
};
