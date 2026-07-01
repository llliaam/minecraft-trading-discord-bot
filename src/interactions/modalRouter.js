// Router semua submit modal. Dipanggil dari events/interactionCreate.js.
//
// Saat ini TIDAK ada modal aktif: pembuatan offer dipindah ke slash command
// /offer (modal Discord tak mendukung autocomplete item — lihat commands/offer.js).
// Kerangka router dibiarkan agar mudah menambah modal baru di masa depan.
import { parseId } from "../lib/ids.js";

/** Entry point: arahkan submit modal ke handler sesuai prefix action. */
export async function handleModal(interaction) {
  const parsed = parseId(interaction.customId);
  if (!parsed) return;

  switch (parsed.action) {
    // Belum ada modal aktif. Tambah case baru di sini bila perlu.
    default:
      return;
  }
}
