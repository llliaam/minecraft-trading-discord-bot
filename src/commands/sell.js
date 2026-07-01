// /sell — buat listing menjual barang.
import {
  buildListingCommandData,
  executeListing,
  autocompleteListing,
} from "./_listingShared.js";

export default {
  data: buildListingCommandData({
    name: "sell",
    description: "Jual sebuah item di marketplace",
  }),
  autocomplete(interaction) {
    return autocompleteListing(interaction);
  },
  execute(interaction) {
    return executeListing(interaction, "SELL");
  },
};
