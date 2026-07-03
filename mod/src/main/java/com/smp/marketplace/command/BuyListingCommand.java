package com.smp.marketplace.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.smp.marketplace.net.ApiClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Command in-game <code>/marketbuy &lt;quantity&gt; &lt;price_qty&gt; [description]</code>.
 *
 * <p>Buat listing BUY (wishlist) — tidak menyetor barang, hanya metadata.
 * Item yang dicari = item di tangan utama; item pembayaran = item di tangan
 * kedua (offhand, tombol F). Konsisten dengan aturan "held item" (lihat
 * CLAUDE.md) — tak ada input teks bebas untuk item.
 */
public final class BuyListingCommand {
    private BuyListingCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, ApiClient api) {
        dispatcher.register(
            literal("marketbuy").then(
                argument("quantity", IntegerArgumentType.integer(1)).then(
                    argument("price_qty", IntegerArgumentType.integer(1))
                        .executes(ctx -> run(ctx, api, null))
                        .then(
                            argument("description", StringArgumentType.greedyString())
                                .executes(ctx -> run(
                                    ctx, api, StringArgumentType.getString(ctx, "description")))
                        )
                )
            )
        );
    }

    private static int run(
            CommandContext<ServerCommandSource> ctx, ApiClient api, String description) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Command ini hanya bisa dipakai oleh pemain."));
            return 0;
        }

        ItemStack wanted = player.getStackInHand(Hand.MAIN_HAND);
        ItemStack payment = player.getStackInHand(Hand.OFF_HAND);
        if (wanted.isEmpty() || payment.isEmpty()) {
            source.sendError(Text.literal(
                "Pegang item yang kamu cari di tangan utama, dan item pembayaran di tangan "
                    + "kedua (offhand, tombol F)."));
            return 0;
        }

        int quantity = IntegerArgumentType.getInteger(ctx, "quantity");
        int priceQuantity = IntegerArgumentType.getInteger(ctx, "price_qty");
        String itemKey = Registries.ITEM.getId(wanted.getItem()).toString();
        String priceItemKey = Registries.ITEM.getId(payment.getItem()).toString();
        String uuid = player.getUuidAsString();
        MinecraftServer server = source.getServer();

        source.sendFeedback(() ->
            Text.literal("Membuat listing...").formatted(Formatting.GRAY), false);

        new Thread(() -> {
            try {
                api.createBuyListing(itemKey, quantity, priceItemKey, priceQuantity, description, uuid);
                server.execute(() -> player.sendMessage(
                    Text.literal("✔ Listing BUY berhasil dibuat!").formatted(Formatting.GREEN), false));
            } catch (ApiClient.ApiException e) {
                server.execute(() -> player.sendMessage(
                    Text.literal("✘ " + e.getMessage()).formatted(Formatting.RED), false));
            }
        }, "smpmarket-buylisting").start();

        return 1;
    }
}