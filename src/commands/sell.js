// /sell — buat listing menjual barang.
import { buildListingCommandData, executeListing } from "./_listingShared.js";

export default {
  data: buildListingCommandData({
    name: "sell",
    description: "Jual sebuah item di marketplace",
  }),
  execute(interaction) {
    return executeListing(interaction, "SELL");
  },
};
