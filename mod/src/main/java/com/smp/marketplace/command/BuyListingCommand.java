package com.smp.marketplace.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.smp.marketplace.gui.BuyPickerGui;
import com.smp.marketplace.net.ApiClient;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Command in-game <code>/marketbuy</code> — buka GUI untuk membuat listing BUY (wishlist).
 *
 * <p>Menggantikan alur held-item lama (main-hand + off-hand + argumen angka). Alasan:
 * item yang dicari maupun item pembayaran tidak perlu dipegang — dipilih dari katalog
 * penuh semua item MC lewat {@link BuyPickerGui}.
 *
 * <p>Listing BUY tidak menyetor barang fisik (tak ada escrow); hanya metadata yang
 * dikirim ke Node setelah pemain mengisi semua langkah di GUI.
 */
public final class BuyListingCommand {
    private BuyListingCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, ApiClient api) {
        dispatcher.register(
            literal("marketbuy").executes(ctx -> run(ctx, api))
        );
    }

    private static int run(CommandContext<ServerCommandSource> ctx, ApiClient api) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Command ini hanya bisa dipakai oleh pemain."));
            return 0;
        }

        new BuyPickerGui(player, api).open();
        return 1;
    }
}
