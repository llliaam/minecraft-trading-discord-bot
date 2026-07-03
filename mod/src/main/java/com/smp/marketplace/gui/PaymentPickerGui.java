package com.smp.marketplace.gui;

import com.smp.marketplace.escrow.EscrowException;
import com.smp.marketplace.escrow.EscrowLedger;
import com.smp.marketplace.net.ApiClient;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SignGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.block.Blocks;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Langkah 2 alur SELL berbasis GUI: <b>pilih item pembayaran + jumlah</b>.
 *
 * <p>Barang yang dijual SUDAH aman di escrow ({@code escrowRef}) sebelum GUI ini
 * dibuka. Di sini pemain memilih <i>label harga</i> dari <b>katalog penuh semua
 * item Minecraft</b> (dari {@link Registries#ITEM}, bukan dari inventory — item
 * pembayaran cuma label, penjual tak perlu memilikinya; lihat CLAUDE.md). Alur:
 * <ol>
 *   <li>Picker paginasi semua item. Tombol cari membuka {@link AnvilInputGui}
 *       (filter live by id + nama).</li>
 *   <li>Klik kiri sebuah item = pilih jenis pembayaran → {@link SignGui} untuk
 *       ketik JUMLAH.</li>
 *   <li>Jumlah &gt; 0 → {@code api.createSellListing} (barang tetap di escrow,
 *       listing jadi ACTIVE). Gagal → refund.</li>
 * </ol>
 *
 * <p><b>Anti-dupe:</b> nasib barang di escrow diputuskan di SATU tempat. Satu-
 * satunya jalur <i>refund</i> adalah menutup picker (batal). Berpindah ke search
 * atau sign memakai flag {@link #navigating} agar {@code onClose} tak refund;
 * search/sign selalu kembali ke picker (escrow utuh). {@link #settled} menjamin
 * refund/commit hanya sekali.
 */
public class PaymentPickerGui {
    private static final int ITEMS_PER_PAGE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_SEARCH = 47;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 51;
    private static final int SLOT_CANCEL = 53;

    /** Katalog penuh (semua item non-AIR), dibangun sekali. */
    private static volatile List<Item> CATALOG;

    private final ServerPlayerEntity player;
    private final MinecraftServer server;
    private final ApiClient api;
    private final EscrowLedger ledger;

    private final String escrowRef;
    private final String soldItemKey;
    private final String soldLabel;
    private final int soldQty;

    private String filter = "";
    private List<Item> filtered;
    private int page = 0;

    private SimpleGui gui;

    /** true saat berpindah ke search/sign — supaya onClose picker tak refund. */
    private boolean navigating = false;
    /** true begitu nasib barang diputuskan (listing dibuat ATAU di-refund). */
    private boolean settled = false;

    public PaymentPickerGui(
            ServerPlayerEntity player,
            ApiClient api,
            String escrowRef,
            String soldItemKey,
            String soldLabel,
            int soldQty) {
        this.player = player;
        this.server = player.getServer();
        this.api = api;
        this.ledger = EscrowLedger.get();
        this.escrowRef = escrowRef;
        this.soldItemKey = soldItemKey;
        this.soldLabel = soldLabel;
        this.soldQty = soldQty;
    }

    public void open() {
        applyFilter("");
        openPicker();
    }

    // ----------------------------------------------------------------------
    // Katalog
    // ----------------------------------------------------------------------

    private static List<Item> catalog() {
        List<Item> c = CATALOG;
        if (c == null) {
            List<Item> built = new ArrayList<>();
            Registries.ITEM.getIds().stream()
                .sorted((a, b) -> a.toString().compareTo(b.toString()))
                .forEach(id -> {
                    Item item = Registries.ITEM.get(id);
                    if (item != Items.AIR) built.add(item);
                });
            CATALOG = built;
            c = built;
        }
        return c;
    }

    private void applyFilter(String raw) {
        this.filter = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        this.page = 0;
        if (filter.isEmpty()) {
            this.filtered = catalog();
            return;
        }
        List<Item> out = new ArrayList<>();
        for (Item item : catalog()) {
            String id = Registries.ITEM.getId(item).toString().toLowerCase(Locale.ROOT);
            String name = new ItemStack(item).getName().getString().toLowerCase(Locale.ROOT);
            if (id.contains(filter) || name.contains(filter)) out.add(item);
        }
        this.filtered = out;
    }

    private int totalPages() {
        return Math.max(1, (int) Math.ceil(filtered.size() / (double) ITEMS_PER_PAGE));
    }

    // ----------------------------------------------------------------------
    // Render picker
    // ----------------------------------------------------------------------

    private void openPicker() {
        navigating = false;
        gui = new SimpleGui(ScreenHandlerType.GENERIC_9X6, player, false) {
            @Override
            public void onClose() {
                if (!navigating && !settled) {
                    refund("Pembuatan listing dibatalkan.");
                }
            }
        };
        gui.setTitle(Text.literal("Pilih item pembayaran").formatted(Formatting.DARK_GREEN));

        int start = page * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= filtered.size()) {
                gui.clearSlot(i);
                continue;
            }
            Item item = filtered.get(idx);
            String key = Registries.ITEM.getId(item).toString();
            String label = new ItemStack(item).getName().getString();
            gui.setSlot(i, new GuiElementBuilder(item)
                .setName(Text.literal(label).formatted(Formatting.WHITE))
                .addLoreLine(text(key, Formatting.DARK_GRAY))
                .addLoreLine(text("Klik untuk pilih sebagai pembayaran", Formatting.YELLOW))
                .setCallback((si, clickType, action, g) -> onPick(key, label))
                .build());
        }

        renderNav();
        gui.open();
    }

    private void renderNav() {
        if (page > 0) {
            gui.setSlot(SLOT_PREV, new GuiElementBuilder(Items.ARROW)
                .setName(text("« Sebelumnya", Formatting.YELLOW))
                .setCallback((i, c, a, g) -> { page--; refreshSlots(); })
                .build());
        } else {
            gui.clearSlot(SLOT_PREV);
        }

        gui.setSlot(SLOT_SEARCH, new GuiElementBuilder(Items.SPYGLASS)
            .setName(text("🔍 Cari item", Formatting.AQUA))
            .addLoreLine(text(filter.isEmpty()
                ? "Klik untuk mengetik kata kunci" : "Filter: " + filter, Formatting.GRAY))
            .setCallback((i, c, a, g) -> openSearch())
            .build());

        gui.setSlot(SLOT_INFO, new GuiElementBuilder(Items.BOOK)
            .setName(text("Menjual: " + soldQty + "× " + soldLabel, Formatting.WHITE))
            .addLoreLine(text("Halaman " + (page + 1) + " / " + totalPages(), Formatting.GRAY))
            .addLoreLine(text(filtered.size() + " item cocok", Formatting.DARK_GRAY))
            .build());

        if (page < totalPages() - 1) {
            gui.setSlot(SLOT_NEXT, new GuiElementBuilder(Items.ARROW)
                .setName(text("Berikutnya »", Formatting.YELLOW))
                .setCallback((i, c, a, g) -> { page++; refreshSlots(); })
                .build());
        } else {
            gui.clearSlot(SLOT_NEXT);
        }

        gui.setSlot(SLOT_CANCEL, new GuiElementBuilder(Items.BARRIER)
            .setName(text("✘ Batal (barang dikembalikan)", Formatting.RED))
            .setCallback((i, c, a, g) -> gui.close())
            .build());
    }

    /** Isi ulang slot item + nav pada halaman/filter berubah (gui sama). */
    private void refreshSlots() {
        int start = page * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= filtered.size()) {
                gui.clearSlot(i);
                continue;
            }
            Item item = filtered.get(idx);
            String key = Registries.ITEM.getId(item).toString();
            String label = new ItemStack(item).getName().getString();
            gui.setSlot(i, new GuiElementBuilder(item)
                .setName(Text.literal(label).formatted(Formatting.WHITE))
                .addLoreLine(text(key, Formatting.DARK_GRAY))
                .addLoreLine(text("Klik untuk pilih sebagai pembayaran", Formatting.YELLOW))
                .setCallback((si, clickType, action, g) -> onPick(key, label))
                .build());
        }
        renderNav();
    }

    // ----------------------------------------------------------------------
    // Search (anvil)
    // ----------------------------------------------------------------------

    private void openSearch() {
        navigating = true;
        gui.close();
        server.execute(() -> {
            AnvilInputGui anvil = new AnvilInputGui(player, false) {
                @Override
                public void onClose() {
                    // Selalu kembali ke picker; escrow tetap utuh.
                    server.execute(PaymentPickerGui.this::openPicker);
                }
            };
            anvil.setTitle(Text.literal("Cari item pembayaran"));
            anvil.setDefaultInputValue(filter);
            // Slot hasil (2) = tombol terapkan filter.
            anvil.setSlot(2, new GuiElementBuilder(Items.LIME_CONCRETE)
                .setName(text("Terapkan pencarian", Formatting.GREEN))
                .setCallback((i, c, a, g) -> {
                    applyFilter(anvil.getInput());
                    anvil.close(); // onClose → openPicker (dengan filter baru).
                }));
            anvil.open();
        });
    }

    // ----------------------------------------------------------------------
    // Pilih pembayaran → sign jumlah → REST
    // ----------------------------------------------------------------------

    private void onPick(String priceItemKey, String priceLabel) {
        navigating = true;
        gui.close();
        server.execute(() -> openQuantitySign(priceItemKey, priceLabel));
    }

    private void openQuantitySign(String priceItemKey, String priceLabel) {
        SignGui sign = new SignGui(player) {
            {
                setSignType(Blocks.OAK_SIGN);
                setColor(DyeColor.BLACK);
                setLine(1, Text.literal("^^^^^^^^^^^^^^^"));
                setLine(2, Text.literal("Jumlah bayaran"));
                setLine(3, Text.literal(priceLabel));
                setAutoUpdate(false);
            }

            @Override
            public void onClose() {
                String raw = getLine(0).getString();
                handleQuantity(priceItemKey, priceLabel, raw);
            }
        };
        sign.open();
    }

    private void handleQuantity(String priceItemKey, String priceLabel, String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        int qty;
        try {
            qty = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            qty = 0;
        }

        if (qty <= 0) {
            // Jumlah kosong/invalid = batal langkah ini; kembali ke picker
            // (escrow tetap utuh, refund hanya bila picker ditutup).
            msg("Jumlah kosong — kembali ke pemilih. Tutup untuk batal & kembalikan barang.",
                Formatting.YELLOW);
            server.execute(this::openPicker);
            return;
        }

        final int quantity = qty;
        final String uuid = player.getUuidAsString();
        msg("Membuat listing SELL...", Formatting.GRAY);

        new Thread(() -> {
            try {
                api.createSellListing(
                    escrowRef, soldItemKey, soldQty, priceItemKey, quantity, null, uuid);
                server.execute(() -> {
                    settled = true; // barang tetap di escrow; listing ACTIVE.
                    player.sendMessage(text(
                        "✔ Listing SELL dibuat! " + soldQty + "× " + soldLabel
                            + " tersimpan di escrow. Harga: " + quantity + "× " + priceLabel,
                        Formatting.GREEN), false);
                });
            } catch (ApiClient.ApiException e) {
                server.execute(() -> refund(e.getMessage()));
            }
        }, "smpmarket-sell-listing").start();
    }

    // ----------------------------------------------------------------------
    // Refund
    // ----------------------------------------------------------------------

    /** Tarik barang dari escrow & kembalikan ke pemain. Idempoten via {@link #settled}. */
    private void refund(String reason) {
        if (settled) return;
        settled = true;
        String opId = UUID.randomUUID().toString();
        try {
            ItemStack back = ledger.withdraw(escrowRef, opId);
            if (!back.isEmpty()) player.getInventory().offerOrDrop(back);
            player.sendMessage(text(
                "✘ " + reason + " Barang dikembalikan ke inventory-mu.", Formatting.RED), false);
        } catch (EscrowException e) {
            player.sendMessage(text(
                "✘ " + reason + " Barang MASIH tersimpan di escrow (ref " + escrowRef
                    + "). Hubungi admin untuk klaim manual.", Formatting.RED), false);
        }
    }

    private void msg(String s, Formatting color) {
        player.sendMessage(text(s, color), false);
    }

    private static Text text(String s, Formatting color) {
        return Text.literal(s).setStyle(Style.EMPTY.withColor(color).withItalic(false));
    }
}
