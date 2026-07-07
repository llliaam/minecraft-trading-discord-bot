// Dipanggil sekali saat bot berhasil login & siap.
import { Events } from "discord.js";
import { startSweeper } from "../services/sweeper.js";

export default {
  name: Events.ClientReady,
  once: true,
  execute(client) {
    console.log(`🤖 Bot online sebagai ${client.user.tag}`);
    startSweeper(client);
  },
};
