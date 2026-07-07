package com.smp.marketplace.gui;

import com.smp.marketplace.model.OfferDto;
import com.smp.marketplace.model.OfferListResult;
import com.smp.marketplace.net.ApiClient;
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
 * GUI chest untuk melihat & merespons offer PENDING yang masuk ke listing
 * milik pemain. Mirror {@link MarketGui} tapi interaktif: klik kiri = accept,
 * klik kanan = reject.
 *
 * <p>Volume SMP kecil biasanya muat 1 halaman; struktur tetap disiapkan untuk
 * tumbuh tapi tak melakukan pagination server-side (bot mengembalikan semua
 * offer PENDING sekaligus).
 */
public class MyOffersGui {
    private static final int[] OFFER_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    private final ServerPlayerEntity player;
    private final MinecraftServer server;
    private final ApiClient api;
    private final String minecraftUuid;

    private SimpleGui gui;

    public MyOffersGui(ServerPlayerEntity player, ApiClient api) {
        this.player = player;
        this.server = player.getServer();
        this.api = api;
        this.minecraftUuid = player.getUuidAsString();
    }

    public void open() {
        loadOffers();
    }

    private void loadOffers() {
        new Thread(() -> {
            try {
                OfferListResult data = api.fetchMyOffers(minecraftUuid);
                server.execute(() -> render(data));
            } catch (ApiClient.ApiException e) {
                server.execute(() -> player.sendMessage(
                    Text.literal("✘ " + e.getMessage()).formatted(Formatting.RED), false));
            }
        }, "smpmarket-myoffers").start();
    }

    private void render(OfferListResult data) {
        if (gui == null) {
            gui = new SimpleGui(ScreenHandlerType.GENERIC_9X6, player, false);
            gui.setTitle(Text.literal("Offer Masuk").formatted(Formatting.DARK_GREEN));
        }

        for (int i = 0; i < 54; i++) gui.clearSlot(i);

        List<OfferDto> offers = data.items != null ? data.items : new ArrayList<>();
        if (offers.isEmpty()) {
            gui.setSlot(22, new GuiElementBuilder(Items.BARRIER)
                .setName(text("Belum ada offer masuk.", Formatting.GRAY))
                .build());
        } else {
            for (int i = 0; i < offers.size() && i < OFFER_SLOTS.length; i++) {
                gui.setSlot(OFFER_SLOTS[i], buildOfferElement(offers.get(i)));
            }
        }

        if (!gui.isOpen()) gui.open();
    }

    private eu.pb4.sgui.api.elements.GuiElementInterface buildOfferElement(OfferDto o) {
        GuiElementBuilder b = new GuiElementBuilder(Items.PAPER)
            .setName(Text.literal("Offer #" + o.id).formatted(Formatting.WHITE))
            .addLoreLine(text("Untuk: " + o.listing.itemLabel, Formatting.GRAY))
            .addLoreLine(text("Tawaran: " + o.priceText, Formatting.GOLD));

        if (o.message != null && !o.message.isBlank()) {
            b.addLoreLine(text("\"" + o.message + "\"", Formatting.DARK_GRAY));
        }
        b.addLoreLine(text("", Formatting.GRAY));
        b.addLoreLine(text("Klik kiri: Terima", Formatting.GREEN));
        b.addLoreLine(text("Klik kanan: Tolak", Formatting.RED));

        b.setCallback((idx, clickType, action, g) -> {
            String decision = clickType.isRight ? "reject" : "left";
            if (!clickType.isLeft && !clickType.isRight) return;
            respond(o.id, clickType.isRight ? "reject" : "accept");
        });

        return b.build();
    }

    private void respond(int offerId, String decision) {
        new Thread(() -> {
            try {
                api.respondOffer(offerId, decision, minecraftUuid);
                server.execute(() -> {
                    player.sendMessage(
                        Text.literal("✔ Offer #" + offerId + " "
                            + ("accept".equals(decision) ? "diterima." : "ditolak."))
                            .formatted(Formatting.GREEN), false);
                    loadOffers();
                });
            } catch (ApiClient.ApiException e) {
                server.execute(() -> player.sendMessage(
                    Text.literal("✘ " + e.getMessage()).formatted(Formatting.RED), false));
            }
        }, "smpmarket-respondoffer").start();
    }

    private static Text text(String s, Formatting color) {
        return Text.literal(s).setStyle(Style.EMPTY.withColor(color).withItalic(false));
    }
}
