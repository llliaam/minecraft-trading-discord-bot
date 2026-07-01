package com.smp.marketplace.gui;

import com.smp.marketplace.net.ApiClient;
import com.smp.marketplace.model.ListingDto;
import com.smp.marketplace.model.ListingPage;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI chest read-only untuk browse listing marketplace in-game.
 *
 * <p>Layout papan 9x6 (54 slot): listing di baris tengah, tombol navigasi
 * (prev/next) di baris paling bawah. Read-only — klik listing belum melakukan
 * apa-apa (aksi beli/nego menyusul di Fase D/E).
 *
 * <p><b>Threading:</b> pengambilan data (HTTP) selalu di thread terpisah agar
 * tick server tidak beku. Pembuatan & pembaruan GUI wajib di thread utama —
 * dijamin lewat {@link MinecraftServer#execute(Runnable)}.
 */
public class MarketGui {
    // Slot tempat listing ditaruh (bot mengirim maksimal 5 per halaman).
    private static final int[] LISTING_SLOTS = {10, 11, 12, 13, 14};
    private static final int SLOT_PREV = 45;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 53;

    private final ServerPlayerEntity player;
    private final MinecraftServer server;
    private final ApiClient api;

    private SimpleGui gui;
    private int currentPage = 1;
    private int totalPages = 1;

    public MarketGui(ServerPlayerEntity player, ApiClient api) {
        this.player = player;
        this.server = player.getServer();
        this.api = api;
    }

    /** Buka GUI: ambil halaman 1 lalu tampilkan. */
    public void open() {
        loadPage(1);
    }

    /**
     * Ambil satu halaman (async) lalu render (di thread utama). Kalau GUI belum
     * dibuka, buat & buka; kalau sudah, perbarui isinya.
     */
    private void loadPage(int page) {
        new Thread(() -> {
            try {
                ListingPage data = api.fetchListings(null, page);
                server.execute(() -> render(data));
            } catch (ApiClient.ApiException e) {
                server.execute(() -> player.sendMessage(
                    Text.literal("✘ " + e.getMessage()).formatted(Formatting.RED), false));
            }
        }, "smpmarket-market").start();
    }

    /** Render data ke GUI (dipanggil di thread utama). */
    private void render(ListingPage data) {
        this.currentPage = data.page;
        this.totalPages = Math.max(1, data.totalPages);

        if (gui == null) {
            gui = new SimpleGui(ScreenHandlerType.GENERIC_9X6, player, false);
            gui.setTitle(Text.literal("Marketplace").formatted(Formatting.DARK_GREEN));
        }

        // Kosongkan semua slot dulu (biar tak ada sisa dari halaman sebelumnya).
        for (int i = 0; i < 54; i++) gui.clearSlot(i);

        List<ListingDto> items = data.items != null ? data.items : new ArrayList<>();
        if (items.isEmpty()) {
            gui.setSlot(22, new GuiElementBuilder(Items.BARRIER)
                .setName(text("Belum ada listing.", Formatting.GRAY))
                .build());
        } else {
            for (int i = 0; i < items.size() && i < LISTING_SLOTS.length; i++) {
                gui.setSlot(LISTING_SLOTS[i], buildListingElement(items.get(i)));
            }
        }

        renderNav();

        if (!gui.isOpen()) gui.open();
    }

    /** Bangun ikon satu listing (PAPER + nama & detail di lore). */
    private eu.pb4.sgui.api.elements.GuiElementInterface buildListingElement(ListingDto l) {
        boolean sell = "SELL".equals(l.type);
        String tag = sell ? "[JUAL]" : "[BELI]";
        Formatting tagColor = sell ? Formatting.GREEN : Formatting.AQUA;

        GuiElementBuilder b = new GuiElementBuilder(Items.PAPER)
            .setName(Text.literal(tag + " ").formatted(tagColor)
                .append(Text.literal(l.itemName).formatted(Formatting.WHITE)))
            .addLoreLine(text("Jumlah: " + l.quantity, Formatting.GRAY))
            .addLoreLine(text("Harga: " + l.price, Formatting.GOLD));

        if (l.description != null && !l.description.isBlank()) {
            b.addLoreLine(text("\"" + l.description + "\"", Formatting.DARK_GRAY));
        }
        b.addLoreLine(text("#" + l.id, Formatting.DARK_GRAY));
        return b.build();
    }

    /** Tombol navigasi prev/next + indikator halaman. */
    private void renderNav() {
        // Prev (aktif hanya bila bukan halaman pertama).
        if (currentPage > 1) {
            gui.setSlot(SLOT_PREV, new GuiElementBuilder(Items.ARROW)
                .setName(text("« Sebelumnya", Formatting.YELLOW))
                .setCallback((idx, clickType, action, g) -> loadPage(currentPage - 1))
                .build());
        }

        // Indikator halaman di tengah.
        gui.setSlot(SLOT_INFO, new GuiElementBuilder(Items.BOOK)
            .setName(text("Halaman " + currentPage + " / " + totalPages, Formatting.WHITE))
            .build());

        // Next (aktif hanya bila masih ada halaman berikutnya).
        if (currentPage < totalPages) {
            gui.setSlot(SLOT_NEXT, new GuiElementBuilder(Items.ARROW)
                .setName(text("Berikutnya »", Formatting.YELLOW))
                .setCallback((idx, clickType, action, g) -> loadPage(currentPage + 1))
                .build());
        }
    }

    /** Text non-italic (lore default MC miring; kita matikan biar rapi). */
    private static Text text(String s, Formatting color) {
        return Text.literal(s).setStyle(Style.EMPTY.withColor(color).withItalic(false));
    }
}
