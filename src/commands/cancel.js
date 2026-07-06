// /cancel — batalkan listing sendiri yang masih ACTIVE.
import { SlashCommandBuilder, MessageFlags } from "discord.js";
import { cancelListing } from "../services/listingService.js";
import { getLinkByDiscord } from "../services/linkService.js";
import { BusinessError } from "../services/transactionService.js";
import { updateListingMessage } from "../lib/marketplace.js";
import { isOnline } from "../lib/onlinePlayers.js";

export default {
  data: new SlashCommandBuilder()
    .setName("cancel")
    .setDescription("Batalkan listing milikmu")
    .addIntegerOption((o) =>
      o
        .setName("id")
        .setDescription("Nomor listing (lihat /mylistings)")
        .setRequired(true)
        .setMinValue(1),
    ),

  async execute(interaction) {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const listingId = interaction.options.getInteger("id", true);

    // Cek apakah pemain sedang online di MC → kalau online, barang langsung
    // dikembalikan ke inventory via mod (bukan mailbox).
    const link = await getLinkByDiscord(interaction.user.id);
    const playerOnline = link ? isOnline(link.minecraftUuid) : false;
    const returnMode = playerOnline ? "ingame" : "mailbox";

    let result;
    try {
      result = await cancelListing({
        listingId,
        actorId: interaction.user.id,
        returnMode,
      });
    } catch (err) {
      if (err instanceof BusinessError) {
        await interaction.editReply({ content: `⚠️ ${err.message}` });
        return;
      }
      throw err;
    }

    const { listing, escrowRef } = result;

    // Update embed di channel marketplace → ❌ Dibatalkan, tanpa tombol.
    await updateListingMessage(interaction.client, listing);

    // Jika returnMode "ingame", mod harus menarik barang dari escrow.
    // Kita panggil endpoint REST mod (atau biarkan mod lakukan via perintah terpisah).
    // Karena Node tidak push ke mod (hanya mod yang pull dari Node), kita simpan
    // escrowRef sebagai "perlu ditarik mod" — dalam praktik: pemain online →
    // sampaikan lewat pesan Discord bahwa mereka perlu ketik /marketcancel <id>
    // ATAU kita ubah agar handler ini langsung kirim REST ke mod via ApiClient Node.
    // Pendekatan sederhana: kalau online → minta pemain ketik /marketcancel in-game.
    // Pendekatan ini konsisten: semua penarikan fisik item hanya dari in-game.
    let note = "";
    if (listing.type === "SELL" && listing.escrowRef) {
      if (playerOnline) {
        note = " Kamu sedang online — ketik **`/marketcancel " + listingId + "`** in-game untuk mengambil barang dari escrow.";
      } else {
        note = " Barangmu menunggu di **mailbox** — ambil in-game dengan `/myclaim` saat kamu login.";
      }
    }

    await interaction.editReply({
      content: `✅ Listing **#${listing.id}** (${listing.itemLabel}) dibatalkan.${note}`,
    });
  },
};
