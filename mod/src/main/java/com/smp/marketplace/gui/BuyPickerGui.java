package com.smp.marketplace.gui;

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

/**
 * Alur GUI untuk membuat listing BUY (wishlist) dari in-game.
 *
 * <p>Langkah:
 * <ol>
 *   <li>Picker katalog — "item yang ingin dibeli" (dari semua item MC).</li>
 *   <li>Picker katalog — "item pembayaran" (GUI sama, label beda).</li>
 *   <li>{@link SignGui} — ketik jumlah item yang dicari.</li>
 *   <li>{@link SignGui} — ketik jumlah pembayaran → {@code api.createBuyListing}.</li>
 * </ol>
 *
 * <p>Listing BUY tidak menyetor barang (tak ada escrow), sehingga tidak ada
 * anti-dupe concern di sini — hanya metadata yang dikirim ke Node.
 */
public class BuyPickerGui {
    private static final int ITEMS_PER_PAGE = 45;
    private static final int SLOT_PREV   = 45;
    private static final int SLOT_SEARCH = 47;
    private static final int SLOT_INFO   = 49;
    private static final int SLOT_NEXT   = 51;
    private static final int SLOT_CANCEL = 53;

    private static volatile List<Item> CATALOG;

    private enum Step { WANT, PAY }

    private final ServerPlayerEntity player;
    private final MinecraftServer server;
    private final ApiClient api;

    // Dipilih di step WANT
    private String wantedItemKey;
    private String wantedLabel;

    // State picker (direset tiap perpindahan step)
    private String filter = "";
    private List<Item> filtered;
    private int page = 0;
    private Step currentStep;

    private SimpleGui gui;
    /** true saat berpindah ke search/sign — agar onClose picker tak tampilkan "dibatalkan". */
    private boolean navigating = false;

    public BuyPickerGui(ServerPlayerEntity player, ApiClient api) {
        this.player = player;
        this.server = player.getServer();
        this.api    = api;
    }

    public void open() {
        currentStep = Step.WANT;
        applyFilter("");
        openPicker();
    }

    // -------------------------------------------------------------------------
    // Katalog
    // -------------------------------------------------------------------------

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
            String id   = Registries.ITEM.getId(item).toString().toLowerCase(Locale.ROOT);
            String name = new ItemStack(item).getName().getString().toLowerCase(Locale.ROOT);
            if (id.contains(filter) || name.contains(filter)) out.add(item);
        }
        this.filtered = out;
    }

    private int totalPages() {
        return Math.max(1, (int) Math.ceil(filtered.size() / (double) ITEMS_PER_PAGE));
    }

    // -------------------------------------------------------------------------
    // Render picker
    // -------------------------------------------------------------------------

    private void openPicker() {
        navigating = false;
        gui = new SimpleGui(ScreenHandlerType.GENERIC_9X6, player, false) {
            @Override
            public void onClose() {
                if (!navigating) {
                    msg("Pembuatan listing BUY dibatalkan.", Formatting.YELLOW);
                }
            }
        };
        gui.setTitle(stepTitle());
        refreshSlots();
        gui.open();
    }

    private Text stepTitle() {
        return currentStep == Step.WANT
            ? Text.literal("Pilih item yang ingin dibeli").formatted(Formatting.DARK_AQUA)
            : Text.literal("Pilih item pembayaran").formatted(Formatting.DARK_GREEN);
    }

    private void refreshSlots() {
        int start = page * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= filtered.size()) {
                gui.clearSlot(i);
                continue;
            }
            Item item = filtered.get(idx);
            String key   = Registries.ITEM.getId(item).toString();
            String label = new ItemStack(item).getName().getString();
            String tip   = currentStep == Step.WANT
                ? "Klik untuk pilih item yang dicari"
                : "Klik untuk pilih sebagai pembayaran";
            gui.setSlot(i, new GuiElementBuilder(item)
                .setName(Text.literal(label).formatted(Formatting.WHITE))
                .addLoreLine(text(key, Formatting.DARK_GRAY))
                .addLoreLine(text(tip, Formatting.YELLOW))
                .setCallback((si, clickType, action, g) -> onPick(key, label))
                .build());
        }
        renderNav();
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

        GuiElementBuilder info = new GuiElementBuilder(Items.BOOK)
            .setName(text(currentStep == Step.WANT
                ? "Langkah 1: Item yang dicari"
                : "Langkah 2: Item pembayaran", Formatting.WHITE))
            .addLoreLine(text("Halaman " + (page + 1) + " / " + totalPages(), Formatting.GRAY))
            .addLoreLine(text(filtered.size() + " item cocok", Formatting.DARK_GRAY));
        if (currentStep == Step.PAY) {
            info.addLoreLine(text("Mencari: " + wantedLabel, Formatting.AQUA));
        }
        gui.setSlot(SLOT_INFO, info.build());

        if (page < totalPages() - 1) {
            gui.setSlot(SLOT_NEXT, new GuiElementBuilder(Items.ARROW)
                .setName(text("Berikutnya »", Formatting.YELLOW))
                .setCallback((i, c, a, g) -> { page++; refreshSlots(); })
                .build());
        } else {
            gui.clearSlot(SLOT_NEXT);
        }

        gui.setSlot(SLOT_CANCEL, new GuiElementBuilder(Items.BARRIER)
            .setName(text("✘ Batal", Formatting.RED))
            .setCallback((i, c, a, g) -> gui.close())
            .build());
    }

    // -------------------------------------------------------------------------
    // Search (anvil)
    // -------------------------------------------------------------------------

    private void openSearch() {
        navigating = true;
        gui.close();
        server.execute(() -> {
            AnvilInputGui anvil = new AnvilInputGui(player, false) {
                @Override
                public void onClose() {
                    // Selalu kembali ke picker; state step tetap utuh.
                    server.execute(BuyPickerGui.this::openPicker);
                }
            };
            anvil.setTitle(Text.literal(currentStep == Step.WANT
                ? "Cari item yang dicari" : "Cari item pembayaran"));
            anvil.setDefaultInputValue(filter);
            anvil.setSlot(2, new GuiElementBuilder(Items.LIME_CONCRETE)
                .setName(text("Terapkan pencarian", Formatting.GREEN))
                .setCallback((i, c, a, g) -> {
                    applyFilter(anvil.getInput());
                    anvil.close(); // onClose → openPicker (dengan filter baru).
                }));
            anvil.open();
        });
    }

    // -------------------------------------------------------------------------
    // Pilih item
    // -------------------------------------------------------------------------

    private void onPick(String key, String label) {
        if (currentStep == Step.WANT) {
            wantedItemKey = key;
            wantedLabel   = label;
            currentStep = Step.PAY;
            applyFilter("");
            navigating = true;
            gui.close();
            server.execute(this::openPicker);
        } else {
            // Step PAY: lanjut ke sign jumlah
            navigating = true;
            gui.close();
            server.execute(() -> openWantedQtySign(key, label));
        }
    }

    // -------------------------------------------------------------------------
    // Sign: jumlah item yang dicari
    // -------------------------------------------------------------------------

    private void openWantedQtySign(String priceItemKey, String priceLabel) {
        SignGui sign = new SignGui(player) {
            {
                setSignType(Blocks.OAK_SIGN);
                setColor(DyeColor.BLACK);
                setLine(1, Text.literal("^^^^^^^^^^^^^^^"));
                setLine(2, Text.literal("Jumlah dicari"));
                setLine(3, Text.literal(wantedLabel));
                setAutoUpdate(false);
            }

            @Override
            public void onClose() {
                handleWantedQty(priceItemKey, priceLabel, getLine(0).getString());
            }
        };
        sign.open();
    }

    private void handleWantedQty(String priceItemKey, String priceLabel, String raw) {
        int qty = parseQty(raw);
        if (qty <= 0) {
            msg("Jumlah kosong — kembali ke pemilih pembayaran. Tutup untuk batal.",
                Formatting.YELLOW);
            currentStep = Step.PAY;
            applyFilter("");
            server.execute(this::openPicker);
            return;
        }
        server.execute(() -> openPriceQtySign(priceItemKey, priceLabel, qty));
    }

    // -------------------------------------------------------------------------
    // Sign: jumlah pembayaran
    // -------------------------------------------------------------------------

    private void openPriceQtySign(String priceItemKey, String priceLabel, int wantedQty) {
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
                handlePriceQty(priceItemKey, priceLabel, wantedQty, getLine(0).getString());
            }
        };
        sign.open();
    }

    private void handlePriceQty(
            String priceItemKey, String priceLabel, int wantedQty, String raw) {
        int priceQty = parseQty(raw);
        if (priceQty <= 0) {
            msg("Jumlah kosong — kembali ke pemilih pembayaran. Tutup untuk batal.",
                Formatting.YELLOW);
            currentStep = Step.PAY;
            applyFilter("");
            server.execute(this::openPicker);
            return;
        }

        final String uuid        = player.getUuidAsString();
        final int finalWantedQty = wantedQty;
        final int finalPriceQty  = priceQty;
        msg("Membuat listing BUY...", Formatting.GRAY);

        new Thread(() -> {
            try {
                api.createBuyListing(
                    wantedItemKey, finalWantedQty, priceItemKey, finalPriceQty, null, uuid);
                server.execute(() -> player.sendMessage(text(
                    "✔ Listing BUY dibuat! Mencari " + finalWantedQty + "× " + wantedLabel
                        + " · Bayar: " + finalPriceQty + "× " + priceLabel,
                    Formatting.GREEN), false));
            } catch (ApiClient.ApiException e) {
                server.execute(() ->
                    player.sendMessage(text("✘ " + e.getMessage(), Formatting.RED), false));
            }
        }, "smpmarket-buy-listing").start();
    }

    // -------------------------------------------------------------------------
    // Utils
    // -------------------------------------------------------------------------

    private static int parseQty(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void msg(String s, Formatting color) {
        player.sendMessage(text(s, color), false);
    }

    private static Text text(String s, Formatting color) {
        return Text.literal(s).setStyle(Style.EMPTY.withColor(color).withItalic(false));
    }
}
