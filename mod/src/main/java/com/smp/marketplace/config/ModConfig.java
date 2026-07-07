package com.smp.marketplace.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.smp.marketplace.SmpMarketMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Konfigurasi mod, dibaca dari <code>config/smpmarket.json</code>.
 *
 * <p><b>Kenapa file, bukan hardcode?</b> Mod perlu tahu <code>apiSecret</code>
 * yang sama dengan <code>.env</code> bot. Secret TIDAK BOLEH masuk ke kode
 * (nanti ikut ter-commit ke git). Maka: saat pertama jalan, mod menulis file
 * config default dengan secret kosong; admin server mengisinya manual.
 *
 * <p>File config ada di folder <code>config/</code> server (di luar jar),
 * jadi aman dari git dan gampang diedit tanpa rebuild.
 */
public class ModConfig {
    /** Alamat REST bot. Loopback: bot & server ada di satu komputer. */
    public String apiBase = "http://127.0.0.1:8765";

    /** Shared-secret; harus sama persis dengan API_SECRET di .env bot. */
    public String apiSecret = "";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Muat config dari disk. Bila file belum ada, tulis default lalu kembalikan
     * default itu (dengan secret kosong — akan gagal auth sampai admin isi).
     */
    public static ModConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("smpmarket.json");

        if (!Files.exists(path)) {
            ModConfig def = new ModConfig();
            try {
                Files.writeString(path, GSON.toJson(def));
                SmpMarketMod.LOGGER.warn(
                    "Config belum ada — dibuat default di {}. ISI 'apiSecret' " +
                    "(samakan dengan API_SECRET di .env bot) lalu restart server.",
                    path);
            } catch (IOException e) {
                SmpMarketMod.LOGGER.error("Gagal menulis config default: {}", e.getMessage());
            }
            return def;
        }

        try {
            ModConfig cfg = GSON.fromJson(Files.readString(path), ModConfig.class);
            if (cfg == null) cfg = new ModConfig();
            if (cfg.apiSecret == null || cfg.apiSecret.isBlank()) {
                SmpMarketMod.LOGGER.warn(
                    "Config 'apiSecret' masih kosong di {} — panggilan ke bot akan " +
                    "ditolak (401). Isi secret-nya lalu restart.", path);
            }
            return cfg;
        } catch (IOException e) {
            SmpMarketMod.LOGGER.error("Gagal membaca config, pakai default: {}", e.getMessage());
            return new ModConfig();
        }
    }
}
