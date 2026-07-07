package com.smp.marketplace.gui;

import com.smp.marketplace.escrow.EscrowException;
import com.smp.marketplace.escrow.EscrowLedger;
import com.smp.marketplace.model.ListingDto;
import com.smp.marketplace.net.ApiClient;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

/**
 * GUI setor pembayaran untuk beli listing SELL (Fase E2).
 *
 * <p>Pemain meletakkan item pembayaran (jenis & jumlah HARUS cocok persis dengan
 * harga listing) ke satu slot custodial, lalu klik konfirmasi. Setelah dikonfirmasi:
 * <ol>
 *   <li>Cek /health (pre-flight) agar pembayaran tidak orphan bila bot mati.</li>
 *   <li>Deposit pembayaran ke {@link EscrowLedger}.</li>
 *   <li>Panggil REST {@code POST /listings/purchase} → bot klaim listing &amp;
 *       kembalikan {@code escrowRef} barang-jual.</li>
 *   <li>Sukses → {@link EscrowLedger#withdraw} barang-jual → serahkan ke pembeli.</li>
 *   <li>Gagal → {@link EscrowLedger#withdraw} pembayaran → kembalikan ke pembeli.</li>
 * </ol>
 *
 * <p><b>Anti-dupe:</b> sama persis dengan {@link SellDepositGui}. Flag {@code handedOff}
 * mencegah {@code onClose} mengembalikan barang setelah konfirmasi diproses.
 */
public class PurchaseDepositGui {
    private static final int SLOT_INPUT = 22;
    private static final int SLOT_CONFIRM = 40;
    private static final int SLOT_INFO = 4;

    private final ServerPlayerEntity player;
    private final MinecraftServer server;
    private final ApiClient api;
    private final EscrowLedger ledger;
    private final ListingDto listing;

    private final SimpleInventory inputInv = new SimpleInventory(1);
    private SimpleGui gui;

    /**
     * true begitu pembayaran berpindah dari slot input ke jalur konfirmasi.
     * Mencegah onClose mengembalikan barang untuk kedua kalinya.
     */
    private boolean handedOff = false;

    public PurchaseDepositGui(ServerPlayerEntity player, ApiClient api, ListingDto listing) {
        this.player = player;
        this.server = player.getServer();
        this.api = api;
        this.listing = listing;
        this.ledger = EscrowLedger.get();
    }

    /** Buka GUI setor pembayaran. */
    public void open() {
        if (!ledger.isReady()) {
            msg("✘ Sistem escrow belum siap. Coba lagi sebentar setelah server memuat.",
                Formatting.RED);
            return;
        }

        gui = new SimpleGui(ScreenHandlerType.GENERIC_9X6, player, false) {
            @Override
            public void onClose() {
                if (!handedOff) returnInput();
            }
        };
        gui.setTitle(Text.literal("Beli: setor pembayaran").formatted(Formatting.GOLD));

        GuiElementBuilder pane = new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE)
            .setName(Text.literal(" "));
        for (int i = 0; i < 54; i++) gui.setSlot(i, pane.build());

        gui.setSlotRedirect(SLOT_INPUT, new Slot(inputInv, 0, 0, 0));

        gui.setSlot(SLOT_INFO, new GuiElementBuilder(Items.GOLD_INGOT)
            .setName(text("Membeli: " + listing.itemLabel, Formatting.WHITE))
            .addLoreLine(text("Jumlah barang: " + listing.quantity, Formatting.GRAY))
            .addLoreLine(text("Harga: " + listing.priceText, Formatting.GOLD))
            .addLoreLine(text("", Formatting.GRAY))
            .addLoreLine(text("Taruh " + listing.priceText + " ke slot tengah,", Formatting.GRAY))
            .addLoreLine(text("lalu klik tombol hijau untuk membeli.", Formatting.GRAY))
            .addLoreLine(text("Item harus TEPAT sesuai harga (jenis & jumlah).", Formatting.DARK_GRAY))
            .build());

        gui.setSlot(SLOT_CONFIRM, new GuiElementBuilder(Items.LIME_CONCRETE)
            .setName(text("✔ Setor pembayaran & beli", Formatting.GREEN))
            .addLoreLine(text("Pastikan item pembayaran sudah di slot tengah.", Formatting.GRAY))
            .setCallback((idx, clickType, action, g) -> onConfirm())
            .build());

        gui.open();
    }

    private void returnInput() {
        for (int i = 0; i < inputInv.size(); i++) {
            ItemStack s = inputInv.getStack(i);
            if (!s.isEmpty()) player.getInventory().offerOrDrop(s.copy());
        }
        inputInv.clear();
    }

    private void onConfirm() {
        ItemStack payment = inputInv.getStack(0);
        if (payment.isEmpty()) {
            msg("Taruh dulu item pembayaran ke slot tengah.", Formatting.YELLOW);
            return;
        }

        // Validasi: jenis item harus cocok.
        String paymentKey = Registries.ITEM.getId(payment.getItem()).toString();
        if (!paymentKey.equals(listing.priceItemKey)) {
            msg("✘ Item salah! Diperlukan: " + listing.priceText
                    + ". Yang kamu taruh: " + payment.getName().getString() + ".",
                Formatting.RED);
            return;
        }
        // Validasi: jumlah harus TEPAT (tidak kurang, tidak lebih).
        if (payment.getCount() != listing.priceQuantity) {
            msg("✘ Jumlah harus tepat " + listing.priceQuantity
                    + "× " + payment.getName().getString()
                    + "! Yang kamu taruh: " + payment.getCount() + "×.",
                Formatting.RED);
            return;
        }

        // Ambil dari slot ke variabel lokal + tandai handedOff sebelum async.
        final ItemStack snapshot = payment.copy();
        inputInv.setStack(0, ItemStack.EMPTY);
        handedOff = true;

        final String uuid = player.getUuidAsString();
        final String name = player.getGameProfile().getName();

        msg("Memeriksa koneksi bot...", Formatting.GRAY);

        new Thread(() -> {
            boolean ok = api.checkHealth();
            server.execute(() -> {
                if (!ok) {
                    gui.close();
                    player.getInventory().offerOrDrop(snapshot);
                    msg("✘ Bot tidak bisa dihubungi. Pembayaran dikembalikan ke inventory-mu.",
                        Formatting.RED);
                    return;
                }

                // Deposit pembayaran ke ledger.
                final String depositOpId = UUID.randomUUID().toString();
                final String paymentEscrowRef;
                try {
                    paymentEscrowRef = ledger.deposit(
                        uuid, name, snapshot, paymentKey,
                        snapshot.getName().getString(), depositOpId);
                } catch (EscrowException e) {
                    gui.close();
                    player.getInventory().offerOrDrop(snapshot);
                    msg("✘ " + e.getMessage() + " Pembayaran dikembalikan.", Formatting.RED);
                    return;
                }

                // Panggil REST purchase. Thread network terpisah, lalu execute kembali ke main.
                final String purchaseOpId = UUID.randomUUID().toString();
                new Thread(() -> {
                    String goodsEscrowRef;
                    try {
                        goodsEscrowRef = api.purchaseListing(
                            listing.id, uuid, paymentEscrowRef, purchaseOpId);
                    } catch (ApiClient.ApiException e) {
                        // REST gagal → refund pembayaran dari escrow ke pembeli.
                        server.execute(() -> {
                            gui.close();
                            final String refundOpId = UUID.randomUUID().toString();
                            try {
                                ItemStack refund = ledger.withdraw(paymentEscrowRef, refundOpId);
                                if (!refund.isEmpty()) {
                                    player.getInventory().offerOrDrop(refund);
                                }
                            } catch (EscrowException ex) {
                                // Withdraw refund gagal — log, tapi jangan crash.
                                msg("✘ Pembayaran ada di escrow (#" + paymentEscrowRef.substring(0, 8)
                                        + "). Hubungi admin untuk pengembalian manual.",
                                    Formatting.RED);
                            }
                            msg("✘ Gagal membeli: " + e.getMessage(), Formatting.RED);
                        });
                        return;
                    }

                    // Sukses: tarik barang-jual dari escrow → serahkan ke pembeli.
                    final String captured = goodsEscrowRef;
                    server.execute(() -> {
                        gui.close();
                        final String withdrawOpId = UUID.randomUUID().toString();
                        try {
                            ItemStack goods = ledger.withdraw(captured, withdrawOpId);
                            if (!goods.isEmpty()) {
                                boolean fitted = player.getInventory().insertStack(goods);
                                if (fitted) {
                                    msg("✅ Berhasil membeli " + listing.itemLabel + " ×" + listing.quantity
                                            + "! Barang sudah di inventory-mu.",
                                        Formatting.GREEN);
                                } else {
                                    player.dropItem(goods, false);
                                    msg("✅ Pembelian berhasil! Inventory penuh — "
                                            + listing.itemLabel + " ×" + listing.quantity
                                            + " dijatuhkan di dekatmu. Segera ambil!",
                                        Formatting.YELLOW);
                                }
                            } else {
                                // Seharusnya tidak terjadi — slot sudah diklaim Node.
                                msg("✅ Transaksi selesai, tapi barang tidak ditemukan di escrow. "
                                        + "Hubungi admin (escrowRef: " + captured.substring(0, 8) + ").",
                                    Formatting.YELLOW);
                            }
                        } catch (EscrowException e) {
                            msg("✘ Barang ada di escrow (#" + captured.substring(0, 8)
                                    + "). Hubungi admin untuk klaim manual.",
                                Formatting.RED);
                        }
                    });
                }, "smpmarket-purchase-rest").start();
            });
        }, "smpmarket-purchase-health").start();
    }

    private void msg(String s, Formatting color) {
        player.sendMessage(text(s, color), false);
    }

    private static Text text(String s, Formatting color) {
        return Text.literal(s).setStyle(Style.EMPTY.withColor(color).withItalic(false));
    }
}
