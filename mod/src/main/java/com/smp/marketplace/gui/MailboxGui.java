package com.smp.marketplace.gui;

import com.smp.marketplace.escrow.EscrowException;
import com.smp.marketplace.escrow.EscrowLedger;
import com.smp.marketplace.model.MailboxItemDto;
import com.smp.marketplace.model.MailboxListResult;
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
 * GUI chest untuk melihat & mengklaim item di mailbox pemain (Fase E3).
 *
 * <p>Mailbox berisi item yang menunggu diambil: pembayaran hasil penjualan
 * ({@code SOLD_PAYMENT}), barang dibatalkan saat offline ({@code CANCELLED_RETURN}),
 * atau sisa overflow klaim sebelumnya ({@code OVERFLOW}).
 *
 * <p><b>Alur klaim per item:</b>
 * <ol>
 *   <li>Klik kiri item di GUI → {@code api.claimMailboxItem(id, uuid)}.</li>
 *   <li>Node menandai item diklaim (idempoten) dan mengembalikan {@code escrowRef}.</li>
 *   <li>Mod {@link EscrowLedger#withdraw} ref tersebut → dapat {@link ItemStack} asli.</li>
 *   <li>Coba {@code offerOrDrop} ke inventory. Karena {@code offerOrDrop} melempar
 *       kelebihan ke lantai (bukan overflow-ke-mailbox-baru), inventory penuh
 *       tidak menyebabkan item hilang — item jatuh di dekat pemain.</li>
 *   <li>Berhasil → reload GUI (item hilang dari daftar).</li>
 * </ol>
 *
 * <p><b>Anti-dupe:</b> Node menandai {@code claimedAt} SEBELUM mengembalikan
 * escrowRef. Kalau mod crash setelah claim tapi sebelum withdraw — slot masih
 * di ledger, tapi metadata sudah "diklaim". Admin harus recover manual via
 * ledger (skenario langka; escrowRef masih valid di disk). Tidak ada jendela
 * double-claim karena Node menolak klaim kedua dengan BusinessError.
 */
public class MailboxGui {
    private static final int[] ITEM_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int SLOT_INFO = 49;

    private final ServerPlayerEntity player;
    private final MinecraftServer server;
    private final ApiClient api;
    private final EscrowLedger ledger;
    private final String minecraftUuid;

    private SimpleGui gui;

    public MailboxGui(ServerPlayerEntity player, ApiClient api) {
        this.player = player;
        this.server = player.getServer();
        this.api = api;
        this.ledger = EscrowLedger.get();
        this.minecraftUuid = player.getUuidAsString();
    }

    public void open() {
        if (!ledger.isReady()) {
            player.sendMessage(
                text("✘ Sistem escrow belum siap. Coba lagi sebentar.", Formatting.RED), false);
            return;
        }
        loadMailbox();
    }

    private void loadMailbox() {
        new Thread(() -> {
            try {
                MailboxListResult data = api.fetchMailbox(minecraftUuid);
                server.execute(() -> render(data));
            } catch (ApiClient.ApiException e) {
                server.execute(() -> player.sendMessage(
                    Text.literal("✘ " + e.getMessage()).formatted(Formatting.RED), false));
            }
        }, "smpmarket-mailbox-load").start();
    }

    private void render(MailboxListResult data) {
        if (gui == null) {
            gui = new SimpleGui(ScreenHandlerType.GENERIC_9X6, player, false);
            gui.setTitle(Text.literal("Mailbox — Item Menunggu").formatted(Formatting.GOLD));
        }

        for (int i = 0; i < 54; i++) gui.clearSlot(i);

        List<MailboxItemDto> items = data.items != null ? data.items : new ArrayList<>();

        if (items.isEmpty()) {
            gui.setSlot(22, new GuiElementBuilder(Items.BARRIER)
                .setName(text("Mailbox kosong.", Formatting.GRAY))
                .addLoreLine(text("Tidak ada item yang menunggu diklaim.", Formatting.DARK_GRAY))
                .build());
        } else {
            for (int i = 0; i < items.size() && i < ITEM_SLOTS.length; i++) {
                gui.setSlot(ITEM_SLOTS[i], buildItemElement(items.get(i)));
            }
        }

        gui.setSlot(SLOT_INFO, new GuiElementBuilder(Items.BOOK)
            .setName(text("Mailbox-mu (" + items.size() + " item)", Formatting.WHITE))
            .addLoreLine(text("Klik kiri item untuk mengambilnya.", Formatting.GRAY))
            .addLoreLine(text("Item jatuh ke lantai bila inventory penuh.", Formatting.DARK_GRAY))
            .build());

        if (!gui.isOpen()) gui.open();
    }

    private eu.pb4.sgui.api.elements.GuiElementInterface buildItemElement(MailboxItemDto m) {
        String reasonLabel = switch (m.reason) {
            case "SOLD_PAYMENT"     -> "Pembayaran dari penjualan";
            case "CANCELLED_RETURN" -> "Barang dibatalkan (cancel listing)";
            case "OVERFLOW"         -> "Sisa klaim sebelumnya (inventory penuh)";
            default                 -> m.reason;
        };

        GuiElementBuilder b = new GuiElementBuilder(Items.CHEST)
            .setName(text(m.itemLabel, Formatting.WHITE))
            .addLoreLine(text("Jumlah: " + m.quantity, Formatting.GRAY))
            .addLoreLine(text("Asal: " + reasonLabel, Formatting.DARK_GRAY))
            .addLoreLine(text("", Formatting.GRAY))
            .addLoreLine(text("Klik kiri: Ambil ke inventory", Formatting.GREEN));

        b.setCallback((idx, clickType, action, g) -> {
            if (clickType.isLeft) claimItem(m);
        });

        return b.build();
    }

    private void claimItem(MailboxItemDto m) {
        new Thread(() -> {
            final String escrowRef;
            try {
                escrowRef = api.claimMailboxItem(m.id, minecraftUuid);
            } catch (ApiClient.ApiException e) {
                server.execute(() -> player.sendMessage(
                    Text.literal("✘ " + e.getMessage()).formatted(Formatting.RED), false));
                return;
            }

            // REST sukses: Node sudah catat klaim. Tarik fisik dari escrow.
            server.execute(() -> {
                String withdrawOpId = UUID.randomUUID().toString();
                try {
                    ItemStack stack = ledger.withdraw(escrowRef, withdrawOpId);
                    if (!stack.isEmpty()) {
                        player.getInventory().offerOrDrop(stack);
                        player.sendMessage(
                            text("✔ Berhasil mengambil " + m.itemLabel
                                + " ×" + m.quantity + " dari mailbox.", Formatting.GREEN),
                            false);
                    } else {
                        // Slot sudah kosong (edge case: crash sebelumnya + re-klaim setelah recovery).
                        player.sendMessage(
                            text("⚠ Item #" + m.id + " sudah tidak ada di escrow. "
                                + "Hubungi admin jika ada kesalahan.", Formatting.YELLOW),
                            false);
                    }
                } catch (EscrowException e) {
                    player.sendMessage(
                        text("✘ Gagal menarik item dari escrow: " + e.getMessage()
                            + " (ref " + escrowRef.substring(0, 8) + "). Hubungi admin.",
                            Formatting.RED),
                        false);
                }

                // Reload GUI agar daftar segar.
                loadMailbox();
            });
        }, "smpmarket-mailbox-claim").start();
    }

    private void msg(String s, Formatting color) {
        player.sendMessage(text(s, color), false);
    }

    private static Text text(String s, Formatting color) {
        return Text.literal(s).setStyle(Style.EMPTY.withColor(color).withItalic(false));
    }
}
