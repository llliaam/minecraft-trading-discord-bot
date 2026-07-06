package com.smp.marketplace.gui;

import com.smp.marketplace.escrow.EscrowException;
import com.smp.marketplace.escrow.EscrowLedger;
import com.smp.marketplace.model.ListingDto;
import com.smp.marketplace.model.ListingPage;
import com.smp.marketplace.net.ApiClient;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GUI chest daftar listing milik pemain sendiri (/mylisting).
 *
 * <p>Menampilkan listing ACTIVE & PENDING. Klik kiri = batalkan listing
 * (in-game, barang langsung balik ke inventory jika SELL).
 */
public class MyListingGui {
    private static final int[] LISTING_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int SLOT_INFO = 49;

    private final ServerPlayerEntity player;
    private final MinecraftServer server;
    private final ApiClient api;
    private final EscrowLedger ledger;
    private final String minecraftUuid;

    private SimpleGui gui;

    public MyListingGui(ServerPlayerEntity player, ApiClient api) {
        this.player = player;
        this.server = player.getServer();
        this.api = api;
        this.ledger = EscrowLedger.get();
        this.minecraftUuid = player.getUuidAsString();
    }

    public void open() {
        loadListings();
    }

    private void loadListings() {
        new Thread(() -> {
            try {
                ListingPage data = api.fetchMyListings(minecraftUuid);
                server.execute(() -> render(data));
            } catch (ApiClient.ApiException e) {
                server.execute(() -> player.sendMessage(
                    Text.literal("✘ " + e.getMessage()).formatted(Formatting.RED), false));
            }
        }, "smpmarket-mylisting-load").start();
    }

    private void render(ListingPage data) {
        if (gui == null) {
            gui = new SimpleGui(ScreenHandlerType.GENERIC_9X6, player, false);
            gui.setTitle(Text.literal("Listing-ku").formatted(Formatting.DARK_GREEN));
        }

        for (int i = 0; i < 54; i++) gui.clearSlot(i);

        List<ListingDto> items = data.items != null ? data.items : new ArrayList<>();

        if (items.isEmpty()) {
            gui.setSlot(22, new GuiElementBuilder(Items.BARRIER)
                .setName(text("Kamu belum punya listing aktif.", Formatting.GRAY))
                .addLoreLine(text("Buat listing SELL dengan /marketsell", Formatting.DARK_GRAY))
                .addLoreLine(text("atau listing BUY dengan /marketbuy.", Formatting.DARK_GRAY))
                .build());
        } else {
            for (int i = 0; i < items.size() && i < LISTING_SLOTS.length; i++) {
                gui.setSlot(LISTING_SLOTS[i], buildListingElement(items.get(i)));
            }
        }

        gui.setSlot(SLOT_INFO, new GuiElementBuilder(Items.BOOK)
            .setName(text("Listing-ku (" + items.size() + ")", Formatting.WHITE))
            .addLoreLine(text("Klik kiri listing untuk membatalkannya.", Formatting.GRAY))
            .addLoreLine(text("Barang SELL dikembalikan ke inventory.", Formatting.DARK_GRAY))
            .build());

        if (!gui.isOpen()) gui.open();
    }

    private eu.pb4.sgui.api.elements.GuiElementInterface buildListingElement(ListingDto l) {
        boolean sell = "SELL".equals(l.type);
        String tag = sell ? "[JUAL]" : "[BELI]";
        Formatting tagColor = sell ? Formatting.GREEN : Formatting.AQUA;

        String statusLabel = switch (l.status != null ? l.status : "ACTIVE") {
            case "ACTIVE"  -> "Aktif";
            case "PENDING" -> "Menunggu trade";
            default        -> l.status;
        };

        GuiElementBuilder b = new GuiElementBuilder(Items.PAPER)
            .setName(Text.literal(tag + " ").formatted(tagColor)
                .append(Text.literal(l.itemLabel).formatted(Formatting.WHITE)))
            .addLoreLine(text("Jumlah: " + l.quantity, Formatting.GRAY))
            .addLoreLine(text("Harga: " + l.priceText, Formatting.GOLD))
            .addLoreLine(text("Status: " + statusLabel, Formatting.YELLOW))
            .addLoreLine(text("#" + l.id, Formatting.DARK_GRAY));

        if (l.description != null && !l.description.isBlank()) {
            b.addLoreLine(text("\"" + l.description + "\"", Formatting.DARK_GRAY));
        }

        b.addLoreLine(text("", Formatting.GRAY));
        b.addLoreLine(text("Klik kiri: Batalkan listing ini", Formatting.RED));

        b.setCallback((idx, clickType, action, g) -> {
            if (clickType.isLeft) cancelListing(l);
        });

        return b.build();
    }

    private void cancelListing(ListingDto l) {
        new Thread(() -> {
            final String escrowRef;
            try {
                escrowRef = api.cancelListing(l.id, minecraftUuid);
            } catch (ApiClient.ApiException e) {
                server.execute(() -> player.sendMessage(
                    Text.literal("✘ " + e.getMessage()).formatted(Formatting.RED), false));
                return;
            }

            server.execute(() -> {
                if (escrowRef == null) {
                    player.sendMessage(text(
                        "✔ Listing #" + l.id + " (" + l.itemLabel + ") dibatalkan.",
                        Formatting.GREEN), false);
                } else {
                    String opId = UUID.randomUUID().toString();
                    try {
                        ItemStack back = ledger.withdraw(escrowRef, opId);
                        if (!back.isEmpty()) {
                            player.getInventory().offerOrDrop(back);
                            player.sendMessage(text(
                                "✔ Listing #" + l.id + " (" + l.itemLabel + ") dibatalkan."
                                    + " Barang dikembalikan ke inventory.",
                                Formatting.GREEN), false);
                        } else {
                            player.sendMessage(text(
                                "✔ Listing #" + l.id + " dibatalkan.",
                                Formatting.GREEN), false);
                        }
                    } catch (EscrowException e) {
                        player.sendMessage(text(
                            "✔ Listing dibatalkan, tapi barang masih di escrow (ref "
                                + escrowRef + "). Hubungi admin.",
                            Formatting.YELLOW), false);
                    }
                }

                // Reload GUI setelah cancel.
                loadListings();
            });
        }, "smpmarket-mylisting-cancel").start();
    }

    private static Text text(String s, Formatting color) {
        return Text.literal(s).setStyle(Style.EMPTY.withColor(color).withItalic(false));
    }
}
