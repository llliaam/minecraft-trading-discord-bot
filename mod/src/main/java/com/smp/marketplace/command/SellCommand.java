package com.smp.marketplace.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.smp.marketplace.gui.SellDepositGui;
import com.smp.marketplace.net.ApiClient;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Command in-game <code>/marketsell</code> — buka GUI setor barang untuk membuat
 * listing SELL (Fase E, revisi GUI).
 *
 * <p>Menggantikan alur held-item lama (main-hand + off-hand). Alasan: item
 * <i>pembayaran</i> yang diinginkan penjual belum tentu ia miliki, jadi tak bisa
 * dipegang di off-hand. Sekarang:
 * <ol>
 *   <li>{@link SellDepositGui} — barang yang dijual ditaruh ke slot GUI
 *       (custodial), lalu disetor ke escrow.</li>
 *   <li>{@code PaymentPickerGui} — item pembayaran dipilih dari katalog PENUH
 *       semua item MC (bukan inventory; item bayar cuma label harga), jumlah
 *       diketik di sign.</li>
 * </ol>
 *
 * <p>Anti-dupe & alur detail lihat kedua kelas GUI + CLAUDE.md.
 */
public final class SellCommand {
    private SellCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, ApiClient api) {
        dispatcher.register(
            literal("marketsell").executes(ctx -> run(ctx, api))
        );
    }

    private static int run(CommandContext<ServerCommandSource> ctx, ApiClient api) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Command ini hanya bisa dipakai oleh pemain."));
            return 0;
        }

        new SellDepositGui(player, api).open();
        return 1;
    }
}
