package com.smp.marketplace.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.smp.marketplace.SmpMarketMod;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Command admin sekali-jalan <code>/smpmarket dumpitems</code>.
 *
 * <p>Menulis SELURUH item dari registry Minecraft (versi server ini, 1.21.1)
 * ke file JSON <code>smpmarket-items.json</code> di working dir server. File itu
 * lalu disalin ke <code>src/lib/itemCatalog.data.json</code> di proyek bot agar
 * autocomplete Discord (/sell /buy /offer) mengenal semua item — bukan cuma
 * subset "barang berharga" yang di-hardcode sebelumnya.
 *
 * <p>Sumber kebenaran item = registry asli, jadi key dijamin kanonik dan cocok
 * untuk pencocokan pembayaran escrow (Fase E). Label diambil dari nama tampilan
 * resmi item (mis. "Diamond Sword"), bukan tebakan Title Case.
 *
 * <p>Butuh level operator (permission level 2) — ini alat admin, bukan fitur
 * pemain biasa.
 */
public final class DumpItemsCommand {
    private DumpItemsCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            literal("smpmarket").requires(src -> src.hasPermissionLevel(2)).then(
                literal("dumpitems").executes(DumpItemsCommand::run)
            )
        );
    }

    private static int run(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();

        // Bangun peta { "minecraft:xxx": "Label" } dari registry, urut menurut key.
        Map<String, String> out = new LinkedHashMap<>();
        Registries.ITEM.getIds().stream()
            .sorted((a, b) -> a.toString().compareTo(b.toString()))
            .forEach(id -> {
                Item item = Registries.ITEM.get(id);
                // Lewati "air" (item kosong) — tak pernah diperdagangkan.
                if (item == net.minecraft.item.Items.AIR) return;
                String label = new ItemStack(item).getName().getString();
                out.put(id.toString(), label);
            });

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(out);

        // Tulis ke working dir server (mod/run saat dev; folder server saat produksi).
        Path target = Path.of("smpmarket-items.json").toAbsolutePath();
        try {
            Files.write(target, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            SmpMarketMod.LOGGER.error("Gagal menulis dump item: {}", e.getMessage());
            source.sendError(Text.literal("Gagal menulis file: " + e.getMessage()));
            return 0;
        }

        final int count = out.size();
        source.sendFeedback(() ->
            Text.literal("✔ " + count + " item ditulis ke " + target)
                .formatted(Formatting.GREEN), false);
        SmpMarketMod.LOGGER.info("Dump {} item ke {}", count, target);
        return 1;
    }
}
