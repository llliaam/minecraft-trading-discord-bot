package com.smp.marketplace.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.smp.marketplace.SmpMarketMod;
import com.smp.marketplace.config.ModConfig;
import com.smp.marketplace.model.ListingPage;
import com.smp.marketplace.model.OfferListResult;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Klien HTTP ke REST bot (Node). Memakai <code>java.net.http</code> bawaan JDK —
 * tak perlu library tambahan.
 *
 * <p>Semua metode melempar {@link ApiException} berisi pesan ramah bila gagal,
 * agar command in-game bisa langsung menampilkannya ke pemain.
 */
public class ApiClient {
    private final ModConfig config;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public ApiClient(ModConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    /** Kesalahan saat memanggil bot (jaringan mati, auth salah, error bisnis). */
    public static class ApiException extends Exception {
        public ApiException(String message) { super(message); }
    }

    /**
     * GET /health — cek bot hidup & bisa dihubungi. Tak melempar; true/false saja.
     * Dipakai pre-flight sebelum menyetor barang ke escrow, agar barang tak jadi
     * orphan (listing tak bisa dibuat) saat bot mati.
     */
    public boolean checkHealth() {
        try {
            HttpResponse<String> res = send("GET", "/health", null);
            return res.statusCode() == 200;
        } catch (ApiException e) {
            return false;
        }
    }

    /**
     * POST /listings/cancel — batalkan listing SELL milik pemain dari in-game.
     * Node melepas metadata listing lalu mengembalikan {@code escrowRef} slot
     * escrow-nya; pemanggil (command) yang menarik barang dari ledger &
     * mengembalikannya ke pemain. Node TAK menyentuh item (lihat CLAUDE.md).
     *
     * @return escrowRef slot yang harus ditarik, atau {@code null} bila listing
     *         itu tak punya escrow (mis. listing BUY).
     * @throws ApiException bila listing tak ditemukan, bukan milik pemain,
     *         sudah ada deal berjalan, atau bot mati.
     */
    public String cancelListing(int listingId, String minecraftUuid) throws ApiException {
        JsonObject payload = new JsonObject();
        payload.addProperty("minecraftUuid", minecraftUuid);
        payload.addProperty("listingId", listingId);

        HttpResponse<String> res = send("POST", "/listings/cancel", gson.toJson(payload));
        if (res.statusCode() == 200) {
            try {
                JsonObject obj = gson.fromJson(res.body(), JsonObject.class);
                if (obj != null && obj.has("escrowRef") && !obj.get("escrowRef").isJsonNull()) {
                    return obj.get("escrowRef").getAsString();
                }
                return null;
            } catch (Exception e) {
                throw new ApiException("Gagal membaca respons pembatalan dari server.");
            }
        }
        throw new ApiException(extractError(res, "Gagal membatalkan listing."));
    }

    /**
     * POST /link/redeem — tukar kode linking dari in-game.
     *
     * @param code          kode 6 karakter yang pemain ketik.
     * @param minecraftUuid UUID pemain (string, dengan tanda hubung).
     * @param minecraftName nama pemain saat ini.
     * @return nama Discord ter-link tak dikembalikan; cukup sukses/gagal.
     * @throws ApiException bila kode invalid/kedaluwarsa, auth salah, atau bot mati.
     */
    public void redeemLink(String code, String minecraftUuid, String minecraftName)
            throws ApiException {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.addProperty("minecraftUuid", minecraftUuid);
        payload.addProperty("minecraftName", minecraftName);

        HttpResponse<String> res = send("POST", "/link/redeem", gson.toJson(payload));

        if (res.statusCode() == 200) {
            return; // sukses
        }

        // Error: coba ambil pesan dari body { "error": "..." }.
        throw new ApiException(extractError(res, "Gagal menautkan akun."));
    }

    /**
     * GET /listings — ambil satu halaman listing ACTIVE untuk ditampilkan di GUI.
     *
     * @param type "SELL" / "BUY" untuk filter, atau null untuk semua.
     * @param page halaman 1-based.
     * @return halaman listing (items + info pagination).
     * @throws ApiException bila auth salah atau bot tak bisa dihubungi.
     */
    public ListingPage fetchListings(String type, int page) throws ApiException {
        StringBuilder path = new StringBuilder("/listings?page=" + page);
        if (type != null) {
            path.append("&type=").append(URLEncoder.encode(type, StandardCharsets.UTF_8));
        }

        HttpResponse<String> res = send("GET", path.toString(), null);

        if (res.statusCode() == 200) {
            try {
                ListingPage parsed = gson.fromJson(res.body(), ListingPage.class);
                if (parsed == null) throw new ApiException("Respons server kosong.");
                return parsed;
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new ApiException("Gagal membaca daftar listing dari server.");
            }
        }

        throw new ApiException(extractError(res, "Gagal mengambil daftar listing."));
    }

    /**
     * POST /listings/buy — buat listing BUY (wishlist) dari in-game.
     *
     * @throws ApiException bila item tak dikenali, belum linked, atau bot mati.
     */
    public void createBuyListing(
            String itemKey,
            int quantity,
            String priceItemKey,
            int priceQuantity,
            String description,
            String minecraftUuid) throws ApiException {
        JsonObject payload = new JsonObject();
        payload.addProperty("minecraftUuid", minecraftUuid);
        payload.addProperty("itemKey", itemKey);
        payload.addProperty("quantity", quantity);
        payload.addProperty("priceItemKey", priceItemKey);
        payload.addProperty("priceQuantity", priceQuantity);
        if (description != null) {
            payload.addProperty("description", description);
        }

        HttpResponse<String> res = send("POST", "/listings/buy", gson.toJson(payload));
        if (res.statusCode() == 200) return;
        throw new ApiException(extractError(res, "Gagal membuat listing."));
    }

    /**
     * POST /listings/sell — buat listing SELL dari in-game. Barang SUDAH disetor
     * ke escrow (ledger) sebelum metode ini dipanggil; {@code escrowRef} menautkan
     * metadata listing ke slot escrow fisiknya.
     *
     * @param escrowRef     id slot escrow tempat barang disimpan.
     * @throws ApiException bila item tak valid, belum linked, atau bot mati.
     */
    public void createSellListing(
            String escrowRef,
            String itemKey,
            int quantity,
            String priceItemKey,
            int priceQuantity,
            String description,
            String minecraftUuid) throws ApiException {
        JsonObject payload = new JsonObject();
        payload.addProperty("minecraftUuid", minecraftUuid);
        payload.addProperty("escrowRef", escrowRef);
        payload.addProperty("itemKey", itemKey);
        payload.addProperty("quantity", quantity);
        payload.addProperty("priceItemKey", priceItemKey);
        payload.addProperty("priceQuantity", priceQuantity);
        if (description != null) {
            payload.addProperty("description", description);
        }

        HttpResponse<String> res = send("POST", "/listings/sell", gson.toJson(payload));
        if (res.statusCode() == 200) return;
        throw new ApiException(extractError(res, "Gagal membuat listing SELL."));
    }

    /**
     * POST /offers — buat offer/nego harga terhadap listing dari in-game.
     *
     * @throws ApiException bila listing tak ditemukan, item tak dikenali, atau bot mati.
     */
    public void createOffer(
            int listingId,
            String priceItemKey,
            int priceQuantity,
            String message,
            String minecraftUuid) throws ApiException {
        JsonObject payload = new JsonObject();
        payload.addProperty("minecraftUuid", minecraftUuid);
        payload.addProperty("listingId", listingId);
        payload.addProperty("priceItemKey", priceItemKey);
        payload.addProperty("priceQuantity", priceQuantity);
        if (message != null) {
            payload.addProperty("message", message);
        }

        HttpResponse<String> res = send("POST", "/offers", gson.toJson(payload));
        if (res.statusCode() == 200) return;
        throw new ApiException(extractError(res, "Gagal membuat offer."));
    }

    /**
     * GET /offers/mine — daftar offer PENDING masuk untuk listing milik pemain.
     *
     * @throws ApiException bila belum linked atau bot mati.
     */
    public OfferListResult fetchMyOffers(String minecraftUuid) throws ApiException {
        String path = "/offers/mine?minecraftUuid="
            + URLEncoder.encode(minecraftUuid, StandardCharsets.UTF_8);

        HttpResponse<String> res = send("GET", path, null);

        if (res.statusCode() == 200) {
            try {
                OfferListResult parsed = gson.fromJson(res.body(), OfferListResult.class);
                if (parsed == null) throw new ApiException("Respons server kosong.");
                return parsed;
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new ApiException("Gagal membaca daftar offer dari server.");
            }
        }

        throw new ApiException(extractError(res, "Gagal mengambil daftar offer."));
    }

    /**
     * POST /offers/respond — accept/reject offer dari in-game.
     *
     * @param decision "accept" atau "reject".
     * @throws ApiException bila offer tak ditemukan, bukan milik pemain, atau bot mati.
     */
    public void respondOffer(int offerId, String decision, String minecraftUuid)
            throws ApiException {
        JsonObject payload = new JsonObject();
        payload.addProperty("minecraftUuid", minecraftUuid);
        payload.addProperty("offerId", offerId);
        payload.addProperty("decision", decision);

        HttpResponse<String> res = send("POST", "/offers/respond", gson.toJson(payload));
        if (res.statusCode() == 200) return;
        throw new ApiException(extractError(res, "Gagal merespons offer."));
    }

    /** Bangun & kirim request; balut error jaringan jadi ApiException. */
    private HttpResponse<String> send(String method, String path, String body)
            throws ApiException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(config.apiBase + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.apiSecret);

        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.GET();
        }

        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            SmpMarketMod.LOGGER.error("Panggilan {} {} gagal: {}", method, path, e.getMessage());
            throw new ApiException(
                "Tidak bisa menghubungi server bot. Pastikan bot Discord sedang berjalan.");
        }
    }

    /** Ambil field "error" dari body JSON, atau pakai fallback. */
    private String extractError(HttpResponse<String> res, String fallback) {
        if (res.statusCode() == 401) {
            return "Konfigurasi mod salah (secret tidak cocok). Hubungi admin server.";
        }
        try {
            JsonObject obj = gson.fromJson(res.body(), JsonObject.class);
            if (obj != null && obj.has("error")) {
                return obj.get("error").getAsString();
            }
        } catch (Exception ignored) {
            // body bukan JSON — pakai fallback.
        }
        return fallback;
    }
}
