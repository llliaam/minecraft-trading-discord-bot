package com.smp.marketplace.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.smp.marketplace.gui.MyOffersGui;
import com.smp.marketplace.net.ApiClient;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Command in-game <code>/myoffers</code> — buka GUI offer PENDING yang masuk
 * ke listing milik pemain (accept/reject). Mirror {@link MarketCommand}.
 */
public final class MyOffersCommand {
    private MyOffersCommand() {}

    public static void register(
            CommandDispatcher<ServerCommandSource> dispatcher, ApiClient api) {

        dispatcher.register(
            literal("myoffers").executes(ctx -> run(ctx, api))
        );
    }

    private static int run(CommandContext<ServerCommandSource> ctx, ApiClient api) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Command ini hanya bisa dipakai oleh pemain."));
            return 0;
        }

        new MyOffersGui(player, api).open();
        return 1;
    }
}
