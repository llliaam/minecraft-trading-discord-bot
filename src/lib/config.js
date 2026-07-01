// Memuat & memvalidasi environment variables di satu tempat.
import "dotenv/config";

function required(name) {
  const value = process.env[name];
  if (!value) {
    console.error(`❌ Environment variable wajib '${name}' belum di-set. Cek file .env.`);
    process.exit(1);
  }
  return value;
}

export const config = {
  token: required("DISCORD_TOKEN"),
  clientId: required("CLIENT_ID"),
  guildId: required("GUILD_ID"),
  marketplaceChannelId: required("MARKETPLACE_CHANNEL_ID"),

  // IPC Bot <-> Mod (Fase C). Opsional: REST server hanya menyala bila
  // apiSecret terisi. Port default 8765 bila tak di-set.
  apiPort: Number(process.env.API_PORT) || 8765,
  apiSecret: process.env.API_SECRET || null,
};
