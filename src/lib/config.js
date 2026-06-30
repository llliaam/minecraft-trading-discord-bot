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
};
