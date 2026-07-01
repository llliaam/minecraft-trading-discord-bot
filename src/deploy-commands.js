// Mendaftarkan slash command ke Discord.
// Saat dev: daftar ke GUILD_ID (instan). Jalankan: npm run deploy
import { REST, Routes } from "discord.js";
import { readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { config } from "./lib/config.js";

const __dirname = dirname(fileURLToPath(import.meta.url));



async function collectCommands() {
  const commandsPath = join(__dirname, "commands");
  const commands = [];
  let files = [];
  try {
    // Skip file berawalan "_" (helper bersama, bukan command).
    files = readdirSync(commandsPath).filter((f) => f.endsWith(".js") && !f.startsWith("_"));
  } catch {
    console.warn("⚠️  Folder commands/ belum ada. Tidak ada yang didaftarkan.");
    return commands;
  }

  for (const file of files) {
    const module = await import(pathToFileURL(join(commandsPath, file)).href);
    const command = module.default;
    if (command?.data) {
      commands.push(command.data.toJSON());
    }
  }
  return commands;
}

async function main() {
  const commands = await collectCommands();
  const rest = new REST().setToken(config.token);

  console.log(`⏳ Mendaftarkan ${commands.length} command ke guild ${config.guildId}...`);
  const data = await rest.put(
    Routes.applicationGuildCommands(config.clientId, config.guildId),
    { body: commands },
  );
  console.log(`✅ ${data.length} command berhasil didaftarkan.`);
}

main().catch((err) => {
  console.error("❌ Gagal mendaftarkan command:", err);
  process.exit(1);
});
