package com.smp.marketplace.gui;

import com.smp.marketplace.escrow.EscrowException;
import com.smp.marketplace.escrow.EscrowLedger;
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
 * Langkah 1 alur SELL berbasis GUI: <b>setor barang custodial</b>.
 *
 * <p>Pemain menaruh barang yang ingin dijual ke satu slot input (di-<i>redirect</i>
 * ke {@link SimpleInventory} milik GUI). Klik tombol konfirmasi → barang disetor
 * ke {@link EscrowLedger} (persist ke disk), lalu {@link PaymentPickerGui} terbuka
 * untuk memilih item pembayaran + jumlah.
 *
 * <p><b>Anti-dupe (titik rawan):</b> saat barang ada di slot input, ia sudah
 * keluar dari inventory pemain dan dipegang oleh {@link SimpleInventory} milik GUI
 * (hanya di memori). Jaring pengaman:
 * <ul>
 *   <li><b>onClose sebelum konfirmasi</b> (tutup/Esc/disconnect) → barang
 *       dikembalikan ke pemain via {@code offerOrDrop}. Idempoten: slot dikosongkan
 *       setelah dikembalikan.</li>
 *   <li><b>Saat konfirmasi</b> → barang diambil dari slot ke variabel lokal
 *       (slot langsung dikosongkan, {@code handedOff=true} agar onClose tak
 *       menggandakan), cek /health, lalu deposit ke ledger. Bila bot mati / deposit
 *       gagal → barang dikembalikan.</li>
 *   <li><b>Hard crash</b> saat barang masih di slot (memori) = barang hilang tapi
 *       TAK PERNAH dobel — sesuai aturan NON-NEGOTIABLE (lihat CLAUDE.md).</li>
 * </ul>
 */
public class SellDepositGui {
    private static final int SLOT_INPUT = 22;
    private static final int SLOT_CONFIRM = 40;
    private static final int SLOT_INFO = 4;

    private final ServerPlayerEntity player;
    private final MinecraftServer server;
    private final ApiClient api;
    private final EscrowLedger ledger;

    /** Wadah custodial slot input (kapasitas 1 stack). */
    private final SimpleInventory inputInv = new SimpleInventory(1);

    private SimpleGui gui;

    /**
     * true begitu barang berpindah dari slot input ke jalur konfirmasi (variabel
     * lokal / ledger). Mencegah onClose mengembalikan barang untuk kedua kalinya.
     */
    private boolean handedOff = false;

    public SellDepositGui(ServerPlayerEntity player, ApiClient api) {
        this.player = player;
        this.server = player.getServer();
        this.api = api;
        this.ledger = EscrowLedger.get();
    }

    /** Buka GUI setor barang. */
    public void open() {
        if (!ledger.isReady()) {
            msg("✘ Sistem escrow belum siap. Coba lagi sebentar setelah server memuat.",
                Formatting.RED);
            return;
        }

        gui = new SimpleGui(ScreenHandlerType.GENERIC_9X6, player, false) {
            @Override
            public void onClose() {
                // Barang belum diserahkan ke jalur konfirmasi → kembalikan.
                if (!handedOff) returnInput();
            }
        };
        gui.setTitle(Text.literal("Jual: taruh barang").formatted(Formatting.DARK_GREEN));

        // Latar: semua slot ditutup panel abu (tak bisa ditaruhi item), kecuali
        // slot input, tombol konfirmasi, dan info.
        GuiElementBuilder pane = new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE)
            .setName(Text.literal(" "));
        for (int i = 0; i < 54; i++) gui.setSlot(i, pane.build());

        // Slot input custodial: redirect ke inventory milik GUI.
        gui.setSlotRedirect(SLOT_INPUT, new Slot(inputInv, 0, 0, 0));

        gui.setSlot(SLOT_INFO, new GuiElementBuilder(Items.BOOK)
            .setName(text("Menjual barang", Formatting.WHITE))
            .addLoreLine(text("Taruh barang yang ingin dijual ke", Formatting.GRAY))
            .addLoreLine(text("slot tengah, lalu klik centang hijau.", Formatting.GRAY))
            .addLoreLine(text("Item pembayaran dipilih di langkah berikutnya.", Formatting.DARK_GRAY))
            .build());

        gui.setSlot(SLOT_CONFIRM, new GuiElementBuilder(Items.LIME_CONCRETE)
            .setName(text("✔ Setor & pilih pembayaran", Formatting.GREEN))
            .addLoreLine(text("Barang akan disimpan di escrow,", Formatting.GRAY))
            .addLoreLine(text("lalu kamu pilih harga.", Formatting.GRAY))
            .setCallback((idx, clickType, action, g) -> onConfirm())
            .build());

        gui.open();
    }

    /** Kembalikan isi slot input ke pemain (idempoten: kosongkan setelahnya). */
    private void returnInput() {
        for (int i = 0; i < inputInv.size(); i++) {
            ItemStack s = inputInv.getStack(i);
            if (!s.isEmpty()) player.getInventory().offerOrDrop(s.copy());
        }
        inputInv.clear();
    }

    private void onConfirm() {
        ItemStack goods = inputInv.getStack(0);
        if (goods.isEmpty()) {
            msg("Taruh dulu barang yang ingin dijual ke slot tengah.", Formatting.YELLOW);
            return;
        }

        // Ambil barang dari slot ke variabel lokal SEKARANG (main thread), lalu
        // kosongkan slot & tandai handedOff — hilangkan balapan dengan onClose /
        // klik pemain selama cek /health async.
        final ItemStack snapshot = goods.copy();
        inputInv.setStack(0, ItemStack.EMPTY);
        handedOff = true;

        final String itemKey = Registries.ITEM.getId(snapshot.getItem()).toString();
        final String itemLabel = snapshot.getName().getString();
        final int quantity = snapshot.getCount();
        final String uuid = player.getUuidAsString();
        final String name = player.getGameProfile().getName();

        msg("Memeriksa koneksi bot...", Formatting.GRAY);

        // Pre-flight /health: hindari barang jadi orphan di escrow saat bot mati
        // (listing tak akan bisa dibuat, dan /marketcancel juga butuh bot).
        new Thread(() -> {
            boolean ok = api.checkHealth();
            server.execute(() -> {
                if (!ok) {
                    gui.close();
                    player.getInventory().offerOrDrop(snapshot);
                    msg("✘ Bot tidak bisa dihubungi. Barang dikembalikan ke inventory-mu.",
                        Formatting.RED);
                    return;
                }

                // Deposit ke ledger (persist ke disk sebelum lanjut).
                final String depositOpId = UUID.randomUUID().toString();
                final String escrowRef;
                try {
                    escrowRef = ledger.deposit(
                        uuid, name, snapshot, itemKey, itemLabel, depositOpId);
                } catch (EscrowException e) {
                    gui.close();
                    player.getInventory().offerOrDrop(snapshot);
                    msg("✘ " + e.getMessage() + " Barang dikembalikan.", Formatting.RED);
                    return;
                }

                // Barang aman di escrow. Lanjut ke pemilih pembayaran.
                gui.close();
                new PaymentPickerGui(
                    player, api, escrowRef, itemKey, itemLabel, quantity).open();
            });
        }, "smpmarket-sell-health").start();
    }

    private void msg(String s, Formatting color) {
        player.sendMessage(text(s, color), false);
    }

    private static Text text(String s, Formatting color) {
        return Text.literal(s).setStyle(Style.EMPTY.withColor(color).withItalic(false));
    }
}
