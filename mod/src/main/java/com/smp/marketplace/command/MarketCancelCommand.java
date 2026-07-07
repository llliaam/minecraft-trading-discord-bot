package com.smp.marketplace.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.smp.marketplace.escrow.EscrowException;
import com.smp.marketplace.escrow.EscrowLedger;
import com.smp.marketplace.net.ApiClient;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Command in-game <code>/marketcancel &lt;listingId&gt;</code> — batalkan listing
 * SELL sendiri & tarik barang kembali dari escrow ke inventory.
 *
 * <p>Alur (pemain online):
 * <ol>
 *   <li>Node membatalkan metadata listing lalu mengembalikan {@code escrowRef}
 *       slot escrow-nya (Node TAK menyentuh item).</li>
 *   <li>Mod menarik stack dari ledger ({@code withdraw}, idempoten via opId) &
 *       mengembalikannya ke pemain ({@code offerOrDrop}).</li>
 * </ol>
 *
 * <p>Untuk listing tanpa escrow (BUY), Node mengembalikan {@code escrowRef=null}
 * dan tak ada barang yang perlu ditarik — cukup konfirmasi pembatalan.
 *
 * <p><b>Anti-dupe:</b> Node menandai listing CANCELLED lebih dulu (metadata),
 * sehingga percobaan cancel kedua tak lagi mengembalikan escrowRef → tak ada
 * withdraw ganda. Withdraw sendiri idempoten (opId unik + slot hilang setelah
 * ditarik). Discord {@code /cancel} (pemain bisa offline) menempuh jalur berbeda:
 * barang tetap di escrow, dialihkan ke MAILBOX untuk diklaim in-game (lihat Node).
 */
public final class MarketCancelCommand {
    private MarketCancelCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, ApiClient api) {
        dispatcher.register(
            literal("marketcancel").then(
                argument("listingId", IntegerArgumentType.integer(1))
                    .executes(ctx -> run(ctx, api))
            )
        );
    }

    private static int run(CommandContext<ServerCommandSource> ctx, ApiClient api) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Command ini hanya bisa dipakai oleh pemain."));
            return 0;
        }

        EscrowLedger ledger = EscrowLedger.get();
        if (!ledger.isReady()) {
            source.sendError(Text.literal(
                "Sistem escrow belum siap. Coba lagi sebentar setelah server memuat."));
            return 0;
        }

        int listingId = IntegerArgumentType.getInteger(ctx, "listingId");
        String uuid = player.getUuidAsString();
        MinecraftServer server = source.getServer();

        source.sendFeedback(() ->
            Text.literal("Membatalkan listing #" + listingId + "...").formatted(Formatting.GRAY),
            false);

        new Thread(() -> {
            final String escrowRef;
            try {
                escrowRef = api.cancelListing(listingId, uuid);
            } catch (ApiClient.ApiException e) {
                server.execute(() -> player.sendMessage(
                    Text.literal("✘ " + e.getMessage()).formatted(Formatting.RED), false));
                return;
            }

            // Metadata sudah dibatalkan di Node. Tarik barang (jika ada escrow).
            server.execute(() -> {
                if (escrowRef == null) {
                    player.sendMessage(Text.literal(
                        "✔ Listing #" + listingId + " dibatalkan.").formatted(Formatting.GREEN),
                        false);
                    return;
                }
                String opId = UUID.randomUUID().toString();
                try {
                    ItemStack back = ledger.withdraw(escrowRef, opId);
                    if (!back.isEmpty()) {
                        player.getInventory().offerOrDrop(back);
                        player.sendMessage(Text.literal(
                            "✔ Listing #" + listingId + " dibatalkan. Barang dikembalikan.")
                            .formatted(Formatting.GREEN), false);
                    } else {
                        // Slot kosong (mis. sudah ditarik) — tetap sukses, tak dobel.
                        player.sendMessage(Text.literal(
                            "✔ Listing #" + listingId + " dibatalkan.")
                            .formatted(Formatting.GREEN), false);
                    }
                } catch (EscrowException e) {
                    player.sendMessage(Text.literal(
                        "⚠ Listing dibatalkan, tapi barang masih di escrow (ref " + escrowRef
                            + "). Hubungi admin.").formatted(Formatting.YELLOW), false);
                }
            });
        }, "smpmarket-cancel").start();

        return 1;
    }
}
