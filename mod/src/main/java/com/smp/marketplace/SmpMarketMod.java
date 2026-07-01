package com.smp.marketplace;

import com.smp.marketplace.command.LinkCommand;
import com.smp.marketplace.command.MarketCommand;
import com.smp.marketplace.config.ModConfig;
import com.smp.marketplace.net.ApiClient;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Titik masuk mod SMP Market (sisi "tangan"/custodian dari sistem marketplace).
 *
 * <p>Fase C2 (bagian kode): membuktikan pipa HTTP dua arah ke bot lewat command
 * in-game <code>/link &lt;kode&gt;</code>. Command <code>/market</code> (GUI
 * browse) & escrow menyusul di sub-fase berikutnya (lihat CLAUDE.md).
 */
public class SmpMarketMod implements ModInitializer {
    public static final String MOD_ID = "smpmarket";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Muat config (apiBase + apiSecret) sekali saat startup.
        ModConfig config = ModConfig.load();
        ApiClient api = new ApiClient(config);

        // Daftarkan command in-game saat server siap menerima registrasi.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LinkCommand.register(dispatcher, api);
            MarketCommand.register(dispatcher, api);
        });

        LOGGER.info("SMP Market mod ter-inisialisasi. Command /link & /market siap.");
    }
}
