package com.smp.marketplace.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.smp.marketplace.net.ApiClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Command in-game <code>/link &lt;kode&gt;</code>.
 *
 * <p>Alur: pemain buat kode di Discord (<code>/link</code>), lalu ketik
 * <code>/link &lt;kode&gt;</code> di server. Mod kirim kode + UUID + nama ke
 * REST bot; bot yang menyimpan tautannya (mod tak sentuh DB).
 *
 * <p>Panggilan HTTP dijalankan di thread terpisah agar tidak membekukan tick
 * server; balasan ke pemain dikembalikan ke thread utama via
 * {@link MinecraftServer#execute(Runnable)}.
 */
public final class LinkCommand {
    private LinkCommand() {}

    public static void register(
            com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher,
            ApiClient api) {

        dispatcher.register(
            literal("link").then(
                argument("code", StringArgumentType.word())
                    .executes(ctx -> run(ctx, api))
            )
        );
    }

    private static int run(CommandContext<ServerCommandSource> ctx, ApiClient api) {
        ServerCommandSource source = ctx.getSource();

        // Hanya pemain (bukan console) yang bisa link — butuh UUID pemain.
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Command ini hanya bisa dipakai oleh pemain."));
            return 0;
        }

        String code = StringArgumentType.getString(ctx, "code");
        String uuid = player.getUuidAsString();
        String name = player.getName().getString();
        MinecraftServer server = source.getServer();

        source.sendFeedback(() ->
            Text.literal("Menautkan akun...").formatted(Formatting.GRAY), false);

        // Kerja HTTP di luar thread utama.
        new Thread(() -> {
            try {
                api.redeemLink(code, uuid, name);
                // Sukses — balik ke thread utama untuk kirim pesan.
                server.execute(() -> player.sendMessage(
                    Text.literal("✔ Akun Discord berhasil ditautkan! Cek dengan /whoami di Discord.")
                        .formatted(Formatting.GREEN), false));
            } catch (ApiClient.ApiException e) {
                server.execute(() -> player.sendMessage(
                    Text.literal("✘ " + e.getMessage()).formatted(Formatting.RED), false));
            }
        }, "smpmarket-link").start();

        return 1;
    }
}
