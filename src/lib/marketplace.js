// Helper terkait channel marketplace: ambil channel & sinkronkan embed listing.
import { config } from "./config.js";
import { buildListingEmbed, buildListingButtons } from "./embeds.js";

/**
 * Ambil channel marketplace yang sudah divalidasi (text-based).
 * @returns {Promise<import("discord.js").TextBasedChannel|null>}
 */
export async function getMarketplaceChannel(client) {
  const channel = await client.channels.fetch(config.marketplaceChannelId).catch(() => null);
  if (!channel || !channel.isTextBased()) return null;
  return channel;
}

/**
 * Sinkronkan pesan embed listing di channel marketplace dengan state terbaru.
 * Dipakai saat status listing berubah (mis. ACTIVE → PENDING → COMPLETED).
 * Aman dipanggil walau pesan/listing tidak punya messageId (akan dilewati).
 * @param {object} [opts]
 * @param {number} [opts.transactionId]  diteruskan ke tombol (Mark Completed saat PENDING).
 */
export async function updateListingMessage(client, listing, { transactionId } = {}) {
  if (!listing.messageId) return;

  const channel = await getMarketplaceChannel(client);
  if (!channel) return;

  const message = await channel.messages.fetch(listing.messageId).catch(() => null);
  if (!message) return;

  await message
    .edit({
      embeds: [buildListingEmbed(listing)],
      components: buildListingButtons(listing, { transactionId }),
    })
    .catch(() => {});
}
