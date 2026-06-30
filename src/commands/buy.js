// /buy — buat listing mencari/ingin membeli barang.
import { buildListingCommandData, executeListing } from "./_listingShared.js";

export default {
  data: buildListingCommandData({
    name: "buy",
    description: "Cari/ingin beli sebuah item (pasang permintaan)",
  }),
  execute(interaction) {
    return executeListing(interaction, "BUY");
  },
};
