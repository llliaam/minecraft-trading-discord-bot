package com.smp.marketplace.gui;

import com.smp.marketplace.net.ApiClient;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SignGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GUI alur "buat offer" dari dalam {@link MarketGui} (klik kanan sebuah listing).
 *
 * <p>Dua langkah:
 * <ol>
 *   <li><b>Pilih item pembayaran</b> — GUI menampilkan item unik dari inventory
 *       pemain sebagai tombol. Pemain klik salah satu untuk memilih JENIS item
 *       pembayaran.</li>
 *   <li><b>Ketik jumlah</b> — sebuah sign terbuka; pemain mengetik angka jumlah
 *       di baris atas (ala DonutSMP).</li>
 * </ol>
 *
 * <p><b>Anti-dupe (penting):</b> offer TIDAK menyetor barang (lihat CLAUDE.md).
 * GUI ini SAMA SEKALI tidak memindahkan item asli — tombol adalah elemen GUI
 * virtual, dan item pemain tak pernah keluar dari inventory. Kita hanya membaca
 * <i>jenis</i> item yang dipilih (id registry). Jadi tak ada kustodi, tak ada
 * jendela crash yang bisa menghilangkan/menggandakan item. Jumlah diambil dari
 * sign, konsisten dengan aturan "harga = item + jumlah".
 */
public class OfferInputGui {
    // 45 slot pertama (baris 0-4) untuk item; baris bawah untuk info.
    private static final int MAX_PICKS = 45;
    private static final int SLOT_INFO = 49;

    private final ServerPlayerEntity player;
    private final MinecraftServer server;
    private final ApiClient api;
    private final int listingId;
    private final String listingLabel;

    private SimpleGui gui;

    public OfferInputGui(
            ServerPlayerEntity player, ApiClient api, int listingId, String listingLabel) {
        this.player = player;
        this.server = player.getServer();
        this.api = api;
        this.listingId = listingId;
        this.listingLabel = listingLabel;
    }

    /** Buka langkah 1: pemilih item pembayaran. */
    public void open() {
        renderPicker();
    }

    /** Kumpulkan jenis item unik dari inventory utama (36 slot) pemain. */
    private List<Item> distinctInventoryItems() {
        List<Item> distinct = new ArrayList<>();
        Set<Item> seen = new HashSet<>();
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            Item it = s.getItem();
            if (seen.add(it)) distinct.add(it);
        }
        return distinct;
    }

    private void renderPicker() {
        gui = new SimpleGui(ScreenHandlerType.GENERIC_9X6, player, false);
        gui.setTitle(Text.literal("Pilih item pembayaran").formatted(Formatting.DARK_GREEN));

        List<Item> items = distinctInventoryItems();
        if (items.isEmpty()) {
            gui.setSlot(22, new GuiElementBuilder(Items.BARRIER)
                .setName(text("Inventory-mu kosong.", Formatting.GRAY))
                .addLoreLine(text("Bawa item yang ingin kamu tawarkan.", Formatting.DARK_GRAY))
                .build());
        } else {
            for (int i = 0; i < items.size() && i < MAX_PICKS; i++) {
                Item item = items.get(i);
                gui.setSlot(i, new GuiElementBuilder(item)
                    .setName(Text.literal(new ItemStack(item).getName().getString())
                        .formatted(Formatting.WHITE))
                    .addLoreLine(text("Klik untuk pilih sebagai pembayaran", Formatting.YELLOW))
                    .setCallback((idx, clickType, action, g) -> onPick(item))
                    .build());
            }
        }

        gui.setSlot(SLOT_INFO, new GuiElementBuilder(Items.BOOK)
            .setName(text("Menawar: " + listingLabel, Formatting.WHITE))
            .addLoreLine(text("Pilih JENIS item pembayaranmu,", Formatting.GRAY))
            .addLoreLine(text("lalu ketik jumlahnya di sign.", Formatting.GRAY))
            .build());

        gui.open();
    }

    /** Langkah 2: jenis item terpilih → buka sign untuk mengetik jumlah. */
    private void onPick(Item item) {
        String priceItemKey = Registries.ITEM.getId(item).toString();
        String label = new ItemStack(item).getName().getString();

        // Tutup pemilih, lalu buka sign di tick berikutnya (transisi layar aman).
        gui.close();
        server.execute(() -> openQuantitySign(priceItemKey, label));
    }

    private void openQuantitySign(String priceItemKey, String label) {
        SignGui sign = new SignGui(player) {
            {
                setSignType(Blocks.OAK_SIGN);
                setColor(DyeColor.BLACK);
                // Baris 0 dibiarkan kosong untuk input; baris bawah = petunjuk.
                setLine(1, Text.literal("^^^^^^^^^^^^^^^"));
                setLine(2, Text.literal("Ketik jumlah"));
                setLine(3, Text.literal(label));
                setAutoUpdate(false);
            }

            @Override
            public void onClose() {
                String raw = getLine(0).getString();
                submit(priceItemKey, label, raw);
            }
        };
        sign.open();
    }

    /** Parse jumlah dari sign & kirim offer (HTTP di thread terpisah). */
    private void submit(String priceItemKey, String label, String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        int qty;
        try {
            qty = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            qty = 0;
        }

        if (qty <= 0) {
            player.sendMessage(Text.literal(
                "✘ Jumlah tidak valid. Offer dibatalkan.").formatted(Formatting.RED), false);
            return;
        }

        final int quantity = qty;
        final String uuid = player.getUuidAsString();

        player.sendMessage(Text.literal("Mengirim offer...").formatted(Formatting.GRAY), false);

        new Thread(() -> {
            try {
                api.createOffer(listingId, priceItemKey, quantity, null, uuid);
                server.execute(() -> player.sendMessage(
                    Text.literal("✔ Offer terkirim: " + quantity + "× " + label
                        + " untuk listing #" + listingId).formatted(Formatting.GREEN), false));
            } catch (ApiClient.ApiException e) {
                server.execute(() -> player.sendMessage(
                    Text.literal("✘ " + e.getMessage()).formatted(Formatting.RED), false));
            }
        }, "smpmarket-offer").start();
    }

    private static Text text(String s, Formatting color) {
        return Text.literal(s).setStyle(Style.EMPTY.withColor(color).withItalic(false));
    }
}
