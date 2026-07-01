// /offer — buat tawaran (usulan harga) untuk sebuah listing.
//
// Kenapa slash command, bukan modal? Modal Discord TIDAK mendukung autocomplete,
// sehingga item pembayaran terpaksa diketik bebas — melenceng dari katalog kanonik.
// Slash command option mendukung autocomplete, jadi item pembayaran dijamin valid
// (konsisten dengan /sell & /buy). Tombol "Make Offer" di embed mengarahkan ke sini.
import { SlashCommandBuilder, MessageFlags } from "discord.js";
import { createOffer } from "../services/offerService.js";
import { BusinessError } from "../services/transactionService.js";
import { buildOfferEmbed, buildOfferButtons } from "../lib/embeds.js";
import { getMarketplaceChannel } from "../lib/marketplace.js";
import { findItems, isValidKey } from "../lib/itemCatalog.js";

export default {
  data: new SlashCommandBuilder()
    .setName("offer")
    .setDescription("Ajukan tawaran harga untuk sebuah listing")
    .addIntegerOption((o) =>
      o
        .setName("id")
        .setDescription("Nomor listing (lihat footer embed / /browse)")
        .setRequired(true)
        .setMinValue(1),
    )
    .addStringOption((o) =>
      o
        .setName("price_item")
        .setDescription("Item pembayaran yang kamu tawarkan (ketik untuk mencari)")
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
    .addStringOption((o) =>
      o.setName("message").setDescription("Pesan tambahan (opsional)").setMaxLength(500),
    ),

  async autocomplete(interaction) {
    const focused = interaction.options.getFocused(true);
    if (focused.name === "price_item") {
      await interaction.respond(findItems(focused.value));
      return;
    }
    await interaction.respond([]);
  },

  async execute(interaction) {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });

    const listingId = interaction.options.getInteger("id", true);
    const priceItemKey = interaction.options.getString("price_item", true).trim();
    const priceQuantity = interaction.options.getInteger("price_qty", true);
    const message = interaction.options.getString("message")?.trim() || null;

    // Validasi item kanonik (autocomplete mengarahkan, tapi pemain bisa ketik bebas).
    if (!isValidKey(priceItemKey)) {
      await interaction.editReply({
        content:
          `⚠️ Item pembayaran **${priceItemKey}** tidak dikenal. Pilih dari daftar saran yang muncul saat mengetik.`,
      });
      return;
    }

    let result;
    try {
      result = await createOffer({
        listingId,
        buyerId: interaction.user.id,
        priceItemKey,
        priceQuantity,
        message,
      });
    } catch (err) {
      if (err instanceof BusinessError) {
        await interaction.editReply({ content: `⚠️ ${err.message}` });
        return;
      }
      throw err;
    }

    const { offer, listing } = result;

    // Ping creator listing di channel marketplace dengan tombol Accept/Reject.
    const channel = await getMarketplaceChannel(interaction.client);
    if (channel) {
      await channel
        .send({
          content: `<@${listing.creatorId}> kamu dapat offer baru!`,
          embeds: [buildOfferEmbed(offer, listing)],
          components: buildOfferButtons(offer),
        })
        .catch(() => {});
    }

    await interaction.editReply({
      content: `✅ Offer **#${offer.id}** terkirim ke <@${listing.creatorId}>.`,
    });
  },
};
