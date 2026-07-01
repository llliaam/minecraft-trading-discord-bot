package com.smp.marketplace.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.smp.marketplace.SmpMarketMod;
import com.smp.marketplace.config.ModConfig;
import com.smp.marketplace.model.ListingPage;

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
