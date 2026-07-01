// /buy — buat listing mencari/ingin membeli barang.
import {
  buildListingCommandData,
  executeListing,
  autocompleteListing,
} from "./_listingShared.js";

export default {
  data: buildListingCommandData({
    name: "buy",
    description: "Cari/ingin beli sebuah item (pasang permintaan)",
  }),
  autocomplete(interaction) {
    return autocompleteListing(interaction);
  },
  execute(interaction) {
    return executeListing(interaction, "BUY");
  },
};
