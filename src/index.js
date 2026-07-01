// Entry point bot: koneksi ke Discord, muat command & event handler.
import { Client, Collection, GatewayIntentBits } from "discord.js";
import { readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { config } from "./lib/config.js";
import { startApiServer } from "./api/server.js";

const __dirname = dirname(fileURLToPath(import.meta.url));

// Intent minimal: bot bekerja lewat slash command & interaksi tombol, 
// tidak perlu membaca isi pesan (MessageContent).
const client = new Client({
  intents: [GatewayIntentBits.Guilds],
});

// Tempat menyimpan command yang dimuat: nama → modul command.
client.commands = new Collection();

// ===== Loader: commands =====
async function loadCommands() {
  const commandsPath = join(__dirname, "commands");
  let files = [];
  try {
    // Skip file berawalan "_" (helper bersama, bukan command).
    files = readdirSync(commandsPath).filter((f) => f.endsWith(".js") && !f.startsWith("_"));
  } catch {
    console.warn("⚠️  Folder commands/ belum ada atau kosong.");
    return;
  }

  for (const file of files) {
    const module = await import(pathToFileURL(join(commandsPath, file)).href);
    const command = module.default;
    if (command?.data && command?.execute) {
      client.commands.set(command.data.name, command);
    } else {
      console.warn(`⚠️  Command di ${file} tidak punya 'data' atau 'execute'. Dilewati.`);
    }
  }
  console.log(`✅ ${client.commands.size} command dimuat.`);
}

// ===== Loader: events =====
async function loadEvents() {
  const eventsPath = join(__dirname, "events");
  let files = [];
  try {
    files = readdirSync(eventsPath).filter((f) => f.endsWith(".js"));
  } catch {
    console.warn("⚠️  Folder events/ belum ada atau kosong.");
    return;
  }

  for (const file of files) {
    const module = await import(pathToFileURL(join(eventsPath, file)).href);
    const event = module.default;
    if (!event?.name || !event?.execute) {
      console.warn(`⚠️  Event di ${file} tidak valid. Dilewati.`);
      continue;
    }
    if (event.once) {
      client.once(event.name, (...args) => event.execute(...args));
    } else {
      client.on(event.name, (...args) => event.execute(...args));
    }
  }
  console.log("✅ Event handler dimuat.");
}

async function main() {
  await loadCommands();
  await loadEvents();
  await client.login(config.token);

  // Nyalakan REST server untuk mod (Fase C). Aman bila API_SECRET kosong:
  // fungsi ini akan melewati start & memberi peringatan.
  startApiServer();
}

main().catch((err) => {
  console.error("❌ Gagal start bot:", err);
  process.exit(1);
});
