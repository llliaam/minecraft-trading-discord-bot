// Handler bersama untuk /sell & /buy (logika sama, beda hanya 'type').
import { SlashCommandBuilder, MessageFlags } from "discord.js";
import { config } from "../lib/config.js";
import { buildListingEmbed, buildListingButtons } from "../lib/embeds.js";
import { createListing, attachMessageId } from "../services/listingService.js";

/**
 * Bangun SlashCommandBuilder untuk listing (dipakai /sell & /buy).
 * @param {object} opts
 * @param {string} opts.name         "sell" | "buy"
 * @param {string} opts.description
 */
export function buildListingCommandData({ name, description }) {
  return new SlashCommandBuilder()
    .setName(name)
    .setDescription(description)
    .addStringOption((o) =>
      o.setName("item").setDescription("Nama item").setRequired(true).setMaxLength(100),
    )
    .addStringOption((o) =>
      o
        .setName("price")
        .setDescription('Harga, teks bebas (mis. "64 diamond")')
        .setRequired(true)
        .setMaxLength(100),
    )
    .addIntegerOption((o) =>
      o
        .setName("qty")
        .setDescription("Jumlah item (default 1)")
        .setMinValue(1)
        .setMaxValue(100000),
    )
    .addStringOption((o) =>
      o.setName("desc").setDescription("Deskripsi tambahan (opsional)").setMaxLength(500),
    );
}

/**
 * Eksekusi pembuatan listing.
 * @param {import("discord.js").ChatInputCommandInteraction} interaction
 * @param {"SELL"|"BUY"} type
 */
export async function executeListing(interaction, type) {
  // Pastikan dijalankan di server (notif kita berbasis channel, bukan DM).
  if (!interaction.inGuild()) {
    await interaction.reply({
      content: "⚠️ Command ini hanya bisa dipakai di dalam server.",
      flags: MessageFlags.Ephemeral,
    });
    return;
  }

  // Defer segera — DB + fetch channel bisa melebihi batas 3 detik Discord.
  await interaction.deferReply({ ephemeral: true });

  const itemName = interaction.options.getString("item", true).trim();
  const price = interaction.options.getString("price", true).trim();
  const quantity = interaction.options.getInteger("qty") ?? 1;
  const description = interaction.options.getString("desc")?.trim() || null;

  // Ambil channel marketplace lebih dulu — kalau gagal, jangan terlanjur simpan listing.
  const channel = await interaction.client.channels
    .fetch(config.marketplaceChannelId)
    .catch(() => null);

  if (!channel || !channel.isTextBased()) {
    await interaction.editReply({
      content:
        "⚠️ Channel marketplace tidak ditemukan atau bukan channel teks. Cek `MARKETPLACE_CHANNEL_ID` di `.env`.",
    });
    return;
  }

  // 1. Simpan listing ke DB.
  const listing = await createListing({
    creatorId: interaction.user.id,
    type,
    itemName,
    quantity,
    price,
    description,
  });

  // 2. Post embed + tombol ke channel marketplace.
  const message = await channel.send({
    embeds: [buildListingEmbed(listing)],
    components: buildListingButtons(listing),
  });

  // 3. Simpan messageId agar embed bisa di-update saat status berubah.
  await attachMessageId(listing.id, message.id);

  // 4. Balas pembuat (ephemeral) dengan tautan ke listing.
  await interaction.editReply({
    content: `✅ Listing **#${listing.id}** dibuat di ${channel}.\n${message.url}`,
  });
}
