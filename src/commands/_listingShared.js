// Handler bersama untuk /sell & /buy (logika sama, beda hanya 'type').
import { SlashCommandBuilder, MessageFlags } from "discord.js";
import { config } from "../lib/config.js";
import { buildListingEmbed, buildListingButtons } from "../lib/embeds.js";
import { createListing, attachMessageId } from "../services/listingService.js";
import { findItems, isValidKey, prettify } from "../lib/itemCatalog.js";

/**
 * Bangun SlashCommandBuilder untuk listing (dipakai /sell & /buy).
 * `item` & `price_item` pakai autocomplete → value = itemKey kanonik.
 * @param {object} opts
 * @param {string} opts.name         "sell" | "buy"
 * @param {string} opts.description
 */
export function buildListingCommandData({ name, description }) {
  return new SlashCommandBuilder()
    .setName(name)
    .setDescription(description)
    .addStringOption((o) =>
      o
        .setName("item")
        .setDescription("Item (ketik untuk mencari)")
        .setRequired(true)
        .setAutocomplete(true),
    )
    .addStringOption((o) =>
      o
        .setName("price_item")
        .setDescription("Item pembayaran (ketik untuk mencari)")
        .setRequired(true)
        .setAutocomplete(true),
    )
    .addIntegerOption((o) =>
      o
        .setName("price_qty")
        .setDescription("Jumlah item pembayaran")
        .setRequired(true)
        .setMinValue(1)
        .setMaxValue(100000),
    )
    .addIntegerOption((o) =>
      o
        .setName("qty")
        .setDescription("Jumlah item yang dijual/dicari (default 1)")
        .setMinValue(1)
        .setMaxValue(100000),
    )
    .addStringOption((o) =>
      o.setName("desc").setDescription("Deskripsi tambahan (opsional)").setMaxLength(500),
    );
}

/**
 * Handler autocomplete untuk /sell & /buy. Opsi `item` dan `price_item`
 * dua-duanya mencari di katalog item. Dipakai oleh sell.js & buy.js.
 * @param {import("discord.js").AutocompleteInteraction} interaction
 */
export async function autocompleteListing(interaction) {
  const focused = interaction.options.getFocused(true);
  if (focused.name === "item" || focused.name === "price_item") {
    await interaction.respond(findItems(focused.value));
    return;
  }
  await interaction.respond([]);
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
  await interaction.deferReply({ flags: MessageFlags.Ephemeral });

  const itemKey = interaction.options.getString("item", true).trim();
  const priceItemKey = interaction.options.getString("price_item", true).trim();
  const priceQuantity = interaction.options.getInteger("price_qty", true);
  const quantity = interaction.options.getInteger("qty") ?? 1;
  const description = interaction.options.getString("desc")?.trim() || null;

  // Validasi: kedua item harus key kanonik dari katalog. Autocomplete sudah
  // mengarahkan ke sini, tapi pemain bisa saja mengetik bebas lalu Enter.
  if (!isValidKey(itemKey)) {
    await interaction.editReply({
      content:
        `⚠️ Item **${itemKey}** tidak dikenal. Pilih dari daftar saran yang muncul saat mengetik.`,
    });
    return;
  }
  if (!isValidKey(priceItemKey)) {
    await interaction.editReply({
      content:
        `⚠️ Item pembayaran **${priceItemKey}** tidak dikenal. Pilih dari daftar saran yang muncul saat mengetik.`,
    });
    return;
  }

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

  // 1. Simpan listing ke DB. itemLabel = label rapi dari katalog (utk tampilan).
  const listing = await createListing({
    creatorId: interaction.user.id,
    type,
    itemKey,
    itemLabel: prettify(itemKey),
    quantity,
    priceItemKey,
    priceQuantity,
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
