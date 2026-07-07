package com.smp.marketplace.net;

import com.google.gson.JsonObject;
import com.smp.marketplace.SmpMarketMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

/**
 * Menerima event WS dari bot dan mengirimkan pesan ke pemain yang sedang online.
 *
 * <p>Dipasang sebagai {@code WsClient.setOnEvent(...)} di
 * {@link com.smp.marketplace.SmpMarketMod}. Semua pesan berjalan di thread WS;
 * pengiriman pesan ke pemain harus dijadwalkan ke main server thread via
 * {@link MinecraftServer#execute}.
 */
public class NotificationHandler {
    private final MinecraftServer server;

    public NotificationHandler(MinecraftServer server) {
        this.server = server;
    }

    /** Entry point: dipanggil oleh WsClient tiap event diterima. */
    public void onEvent(String event, JsonObject data) {
        try {
            switch (event) {
                case "OFFER_RECEIVED"  -> handleOfferReceived(data);
                case "OFFER_ACCEPTED"  -> handleOfferAccepted(data);
                case "OFFER_REJECTED"  -> handleOfferRejected(data);
                case "ITEM_SOLD"       -> handleItemSold(data);
                case "LISTING_EXPIRED" -> handleListingExpired(data);
                default -> SmpMarketMod.LOGGER.debug("WS: event tidak dikenal — {}", event);
            }
        } catch (Exception e) {
            SmpMarketMod.LOGGER.warn("WS: error saat handle event {} — {}", event, e.getMessage());
        }
    }

    // ─── Handler per event ────────────────────────────────────────────────────

    private void handleOfferReceived(JsonObject data) {
        String uuid      = str(data, "targetUuid");
        int listingId    = data.get("listingId").getAsInt();
        int offerId      = data.get("offerId").getAsInt();
        String itemLabel = str(data, "itemLabel");
        String priceText = str(data, "priceText");

        ping(uuid, text(
            "💬 Offer baru untuk listing #" + listingId + " (" + itemLabel + ")! " +
            "Harga ditawarkan: " + priceText + ". " +
            "Balas via /myoffers atau Discord.",
            Formatting.YELLOW));
    }

    private void handleOfferAccepted(JsonObject data) {
        String uuid      = str(data, "targetUuid");
        int listingId    = data.get("listingId").getAsInt();
        String itemLabel = str(data, "itemLabel");

        ping(uuid, text(
            "✅ Offer-mu untuk listing #" + listingId + " (" + itemLabel + ") DITERIMA! " +
            "Datang in-game dan bayar dalam 24 jam sebelum reservasi kedaluwarsa.",
            Formatting.GREEN));
    }

    private void handleOfferRejected(JsonObject data) {
        String uuid   = str(data, "targetUuid");
        int offerId   = data.get("offerId").getAsInt();

        ping(uuid, text(
            "❌ Offer #" + offerId + "-mu ditolak oleh penjual.",
            Formatting.RED));
    }

    private void handleItemSold(JsonObject data) {
        String uuid      = str(data, "targetUuid");
        int listingId    = data.get("listingId").getAsInt();
        String itemLabel = str(data, "itemLabel");
        int quantity     = data.get("quantity").getAsInt();

        ping(uuid, text(
            "💰 " + quantity + "× " + itemLabel + " di listing #" + listingId + " TERJUAL! " +
            "Pembayaran sudah masuk mailbox-mu. Klaim dengan /myclaim.",
            Formatting.GOLD));
    }

    private void handleListingExpired(JsonObject data) {
        String uuid      = str(data, "targetUuid");
        int listingId    = data.get("listingId").getAsInt();
        String itemLabel = str(data, "itemLabel");
        boolean hasMail  = data.has("hasMail") && data.get("hasMail").getAsBoolean();

        String suffix = hasMail
            ? " Barang-mu tersimpan di mailbox — klaim dengan /myclaim."
            : "";
        ping(uuid, text(
            "⏳ Listing #" + listingId + " (" + itemLabel + ") sudah kadaluarsa." + suffix,
            Formatting.GRAY));
    }

    // ─── Kirim pesan ke pemain online ─────────────────────────────────────────

    /**
     * Kirim pesan ke pemain bila sedang online. Dijadwalkan ke main server thread.
     * @param uuidStr UUID Minecraft pemain (string dengan tanda hubung).
     * @param message pesan yang dikirim ke chat pemain.
     */
    private void ping(String uuidStr, Text message) {
        if (uuidStr == null || uuidStr.isEmpty()) return;
        try {
            UUID uuid = UUID.fromString(uuidStr);
            server.execute(() -> {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player != null) {
                    player.sendMessage(message, false);
                }
                // Pemain offline → tidak ada aksi (Discord sudah ping via channel).
            });
        } catch (IllegalArgumentException e) {
            SmpMarketMod.LOGGER.warn("WS: UUID tidak valid — {}", uuidStr);
        }
    }

    // ─── Utils ────────────────────────────────────────────────────────────────

    private static String str(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return "";
        return obj.get(key).getAsString();
    }

    private static Text text(String s, Formatting color) {
        return Text.literal(s).setStyle(Style.EMPTY.withColor(color).withItalic(false));
    }
}
