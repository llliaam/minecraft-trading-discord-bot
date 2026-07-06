package com.smp.marketplace;

import com.smp.marketplace.command.BuyListingCommand;
import com.smp.marketplace.command.ClaimCommand;
import com.smp.marketplace.command.DumpItemsCommand;
import com.smp.marketplace.command.LinkCommand;
import com.smp.marketplace.command.MarketCancelCommand;
import com.smp.marketplace.command.MarketCommand;
import com.smp.marketplace.command.MyListingCommand;
import com.smp.marketplace.command.MyOffersCommand;
import com.smp.marketplace.command.SellCommand;
import com.smp.marketplace.config.ModConfig;
import com.smp.marketplace.escrow.EscrowLedger;
import com.smp.marketplace.net.ApiClient;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Titik masuk mod SMP Market (sisi "tangan"/custodian dari sistem marketplace).
 *
 * <p>Fase C2: membuktikan pipa HTTP dua arah ke bot lewat command in-game
 * <code>/link &lt;kode&gt;</code> dan <code>/market</code> (GUI browse
 * ). Fase D menambah aksi tulis non-item dari in-game:
 * <code>/marketbuy</code> (listing BUY) dan <code>/myoffers</code>
 * (accept/reject offer masuk). Membuat offer kini lewat GUI <code>/market</code>
 * (klik kanan sebuah listing), bukan command terpisah.
 * <code>/smpmarket dumpitems</code> (admin) meng-export seluruh item registry
 * ke JSON untuk katalog Discord. Escrow menyusul di Fase E (lihat CLAUDE.md).
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
            BuyListingCommand.register(dispatcher, api);
            MyOffersCommand.register(dispatcher, api);
            SellCommand.register(dispatcher, api);
            MarketCancelCommand.register(dispatcher, api);
            ClaimCommand.register(dispatcher, api);
            MyListingCommand.register(dispatcher, api);
            DumpItemsCommand.register(dispatcher);
        });

        // Escrow (Fase E): muat ledger saat server siap = sumber kebenaran item.
        // Persist tiap mutasi sudah ditangani EscrowLedger; hook STOPPING hanya
        // jaring pengaman flush terakhir.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> EscrowLedger.get().load(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> EscrowLedger.get().shutdown());

        // Sync presence pemain ke bot agar /cancel Discord bisa cek online status.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            String uuid = handler.player.getUuidAsString();
            new Thread(() -> {
                try { api.playerJoin(uuid); }
                catch (ApiClient.ApiException e) {
                    LOGGER.warn("Gagal lapor join pemain {} ke bot: {}", uuid, e.getMessage());
                }
            }, "smpmarket-presence-join").start();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            String uuid = handler.player.getUuidAsString();
            new Thread(() -> {
                try { api.playerQuit(uuid); }
                catch (ApiClient.ApiException e) {
                    LOGGER.warn("Gagal lapor quit pemain {} ke bot: {}", uuid, e.getMessage());
                }
            }, "smpmarket-presence-quit").start();
        });

        LOGGER.info(
            "SMP Market mod ter-inisialisasi. Command /link /market /marketbuy "
                + "/myoffers /marketsell /marketcancel /myclaim /mylisting siap (offer via GUI /market).");
    }
}
