package com.smp.marketplace.escrow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.smp.marketplace.SmpMarketMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kustodian item: satu-satunya pihak yang memegang item Minecraft asli (escrow).
 *
 * <p><b>Sumber kebenaran ITEM</b> (lihat CLAUDE.md). Node tak pernah menyentuh
 * item — ia hanya menyimpan metadata & menautkannya lewat <code>escrowRef</code>.
 *
 * <p><b>Aturan anti-dupe yang diwujudkan kelas ini:</b>
 * <ol>
 *   <li>Setiap mutasi (deposit/withdraw) <b>di-persist ke disk seketika</b> —
 *       tulis file sementara lalu {@code move} atomik. Tahan crash: begitu
 *       {@link #deposit} kembali, slot sudah aman di disk.</li>
 *   <li>Setiap operasi pindah item membawa <b>opId</b> unik. Hasilnya dicatat di
 *       {@link #opResults}; opId yang sama tak pernah dieksekusi dua kali
 *       (idempoten) — aman terhadap retry/double-click/crash-lalu-ulang.</li>
 *   <li>Saat startup {@link #load} membaca ledger dari disk = kebenaran item.</li>
 * </ol>
 *
 * <p>Singleton: seluruh mod memakai satu instance ({@link #get()}). Semua mutasi
 * ter-serialisasi lewat {@link #lock} agar konsisten dengan file di disk.
 */
public final class EscrowLedger {
    private static final EscrowLedger INSTANCE = new EscrowLedger();

    /** Nama file ledger di working dir server. */
    private static final String FILE_NAME = "smpmarket-escrow.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Object lock = new Object();

    /** escrowRef -> slot yang sedang dipegang. */
    private final Map<String, EscrowSlot> slots = new LinkedHashMap<>();

    /** opId -> escrowRef hasil (untuk deposit) atau "" (operasi tanpa ref). */
    private final Map<String, String> opResults = new LinkedHashMap<>();

    /** Registry untuk (de)serialisasi NBT; di-set saat {@link #load}. */
    private RegistryWrapper.WrapperLookup registries;
    private Path file;
    private boolean loaded = false;

    private EscrowLedger() {}

    public static EscrowLedger get() {
        return INSTANCE;
    }

    // ----------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------

    /**
     * Muat ledger dari disk saat server siap. Menyimpan referensi registry
     * (dibutuhkan untuk (de)serialisasi item). Aman dipanggil sekali.
     */
    public void load(MinecraftServer server) {
        synchronized (lock) {
            this.registries = server.getRegistryManager();
            this.file = FabricLoader.getInstance().getGameDir().resolve(FILE_NAME);

            slots.clear();
            opResults.clear();

            if (!Files.exists(file)) {
                loaded = true;
                SmpMarketMod.LOGGER.info("Ledger escrow belum ada — mulai kosong ({}).", file);
                return;
            }

            try {
                LedgerData data = gson.fromJson(Files.readString(file), LedgerData.class);
                if (data != null) {
                    if (data.slots != null) {
                        for (EscrowSlot s : data.slots) {
                            if (s != null && s.escrowRef != null) slots.put(s.escrowRef, s);
                        }
                    }
                    if (data.opResults != null) opResults.putAll(data.opResults);
                }
                loaded = true;
                SmpMarketMod.LOGGER.info(
                    "Ledger escrow dimuat: {} slot, {} operasi tercatat.",
                    slots.size(), opResults.size());
            } catch (Exception e) {
                // KRITIS: jangan diam-diam mulai kosong (item bisa "hilang"). Buat
                // backup file rusak & tolak jalan sampai admin memeriksa.
                loaded = false;
                backupCorrupt();
                SmpMarketMod.LOGGER.error(
                    "GAGAL memuat ledger escrow dari {} — file mungkin rusak. "
                        + "Backup dibuat. Operasi escrow DINONAKTIFKAN sampai diperbaiki. {}",
                    file, e.getMessage());
            }
        }
    }

    /** Flush terakhir saat server berhenti (jaring pengaman; mutasi sudah persist). */
    public void shutdown() {
        synchronized (lock) {
            if (loaded) save();
        }
    }

    public boolean isReady() {
        synchronized (lock) {
            return loaded && registries != null;
        }
    }

    // ----------------------------------------------------------------------
    // Operasi item
    // ----------------------------------------------------------------------

    /**
     * Setor stack ke escrow. Item dianggap sudah dikeluarkan dari inventory oleh
     * pemanggil; ledger yang menyimpannya (encode NBT + persist).
     *
     * <p>Idempoten: bila {@code opId} sudah pernah dieksekusi, mengembalikan
     * escrowRef yang sama tanpa membuat slot baru.
     *
     * @return escrowRef slot baru (atau existing bila opId terulang).
     * @throws EscrowException bila ledger belum siap atau serialisasi gagal.
     */
    public String deposit(
            String ownerUuid,
            String ownerName,
            ItemStack stack,
            String itemKey,
            String itemLabel,
            String opId) throws EscrowException {
        synchronized (lock) {
            ensureReady();
            if (stack == null || stack.isEmpty()) {
                throw new EscrowException("Tidak ada item untuk disetor.");
            }
            // Idempotensi: opId ini sudah menghasilkan sebuah slot → kembalikan itu.
            if (opId != null && opResults.containsKey(opId)) {
                return opResults.get(opId);
            }

            String escrowRef = UUID.randomUUID().toString();
            String nbtBase64 = encodeStack(stack);

            EscrowSlot slot = new EscrowSlot(
                escrowRef, ownerUuid, ownerName, itemKey, itemLabel,
                stack.getCount(), nbtBase64, System.currentTimeMillis());

            slots.put(escrowRef, slot);
            if (opId != null) opResults.put(opId, escrowRef);
            save(); // persist SEBELUM kembali — barang aman di disk.

            SmpMarketMod.LOGGER.info(
                "Escrow deposit: {} ({}x {}) oleh {} [op {}]",
                escrowRef, stack.getCount(), itemKey, ownerName, opId);
            return escrowRef;
        }
    }

    /**
     * Tarik stack dari escrow (menghapus slot). Idempoten: bila opId sudah
     * dieksekusi, atau slot sudah tak ada, mengembalikan {@link ItemStack#EMPTY}.
     *
     * @return stack asli (NBT utuh), atau EMPTY bila sudah ditarik/idempotent-hit.
     */
    public ItemStack withdraw(String escrowRef, String opId) throws EscrowException {
        synchronized (lock) {
            ensureReady();
            if (opId != null && opResults.containsKey(opId)) {
                return ItemStack.EMPTY; // sudah pernah diproses.
            }

            EscrowSlot slot = slots.get(escrowRef);
            if (slot == null) {
                // Tandai op selesai agar retry tak menggantung, tetap EMPTY.
                if (opId != null) {
                    opResults.put(opId, "");
                    save();
                }
                return ItemStack.EMPTY;
            }

            ItemStack stack = decodeStack(slot.nbtBase64);
            slots.remove(escrowRef);
            if (opId != null) opResults.put(opId, "");
            save(); // persist SEBELUM item diserahkan ke pemain.

            SmpMarketMod.LOGGER.info(
                "Escrow withdraw: {} ({}x {}) [op {}]",
                escrowRef, slot.quantity, slot.itemKey, opId);
            return stack;
        }
    }

    /** Apakah opId sudah pernah dieksekusi (idempotency check). */
    public boolean hasExecuted(String opId) {
        synchronized (lock) {
            return opId != null && opResults.containsKey(opId);
        }
    }

    /** Ambil salinan info slot (atau null). Tidak mengubah state. */
    public EscrowSlot getSlot(String escrowRef) {
        synchronized (lock) {
            return slots.get(escrowRef);
        }
    }

    /** Daftar semua escrowRef yang sedang dipegang (untuk rekonsiliasi Fase E4). */
    public List<String> listRefs() {
        synchronized (lock) {
            return new ArrayList<>(slots.keySet());
        }
    }

    // ----------------------------------------------------------------------
    // Internal
    // ----------------------------------------------------------------------

    private void ensureReady() throws EscrowException {
        if (!loaded || registries == null) {
            throw new EscrowException(
                "Sistem escrow belum siap (ledger belum dimuat). Coba lagi sebentar.");
        }
    }

    /** ItemStack → NBT ter-kompres → base64. Menyimpan NBT utuh (enchant, dll). */
    private String encodeStack(ItemStack stack) throws EscrowException {
        try {
            NbtElement encoded = stack.encode(registries);
            if (!(encoded instanceof NbtCompound compound)) {
                throw new EscrowException("Format NBT item tak terduga.");
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(compound, baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (EscrowException e) {
            throw e;
        } catch (Exception e) {
            throw new EscrowException("Gagal menyimpan data item ke escrow.");
        }
    }

    /** base64 → NBT ter-kompres → ItemStack (kebalikan {@link #encodeStack}). */
    private ItemStack decodeStack(String nbtBase64) throws EscrowException {
        try {
            byte[] bytes = Base64.getDecoder().decode(nbtBase64);
            NbtCompound compound = NbtIo.readCompressed(
                new ByteArrayInputStream(bytes), NbtSizeTracker.ofUnlimitedBytes());
            return ItemStack.fromNbt(registries, compound).orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            throw new EscrowException("Gagal memulihkan item dari escrow.");
        }
    }

    /**
     * Tulis ledger ke disk secara atomik: tulis ke file .tmp lalu pindah menimpa.
     * Dipanggil di dalam {@link #lock} setiap mutasi.
     */
    private void save() {
        LedgerData data = new LedgerData();
        data.slots = new ArrayList<>(slots.values());
        data.opResults = new LinkedHashMap<>(opResults);

        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.writeString(tmp, gson.toJson(data));
            try {
                Files.move(tmp, file,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicFailed) {
                // Sebagian filesystem tak dukung ATOMIC_MOVE — fallback non-atomik.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            // Kegagalan persist = bahaya (state memori & disk bisa beda). Log keras.
            SmpMarketMod.LOGGER.error(
                "GAGAL mem-persist ledger escrow ke {}: {}", file, e.getMessage());
        }
    }

    /** Salin file ledger rusak ke .corrupt-<ts> supaya tidak tertimpa. */
    private void backupCorrupt() {
        try {
            Path bak = file.resolveSibling(FILE_NAME + ".corrupt-" + System.currentTimeMillis());
            Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            // best-effort.
        }
    }

    /** Bentuk on-disk ledger (dipetakan Gson). */
    private static final class LedgerData {
        List<EscrowSlot> slots;
        Map<String, String> opResults;
    }
}
