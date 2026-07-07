package com.smp.marketplace.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.smp.marketplace.escrow.EscrowLedger;
import com.smp.marketplace.gui.MailboxGui;
import com.smp.marketplace.net.ApiClient;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Command in-game {@code /myclaim} — buka GUI mailbox pemain untuk melihat
 * & mengklaim item yang menunggu (pembayaran masuk, barang dibatalkan, dll).
 *
 * <p>Logika klaim sepenuhnya ada di {@link MailboxGui}: REST ke Node
 * ({@code POST /mailbox/claim}) lalu {@code EscrowLedger.withdraw}.
 */
public final class ClaimCommand {
    private ClaimCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, ApiClient api) {
        dispatcher.register(literal("myclaim").executes(ctx -> run(ctx, api)));
    }

    private static int run(CommandContext<ServerCommandSource> ctx, ApiClient api) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Command ini hanya bisa dipakai oleh pemain."));
            return 0;
        }

        if (!EscrowLedger.get().isReady()) {
            source.sendError(Text.literal(
                "Sistem escrow belum siap. Coba lagi sebentar setelah server memuat."));
            return 0;
        }

        new MailboxGui(player, api).open();
        return 1;
    }
}
