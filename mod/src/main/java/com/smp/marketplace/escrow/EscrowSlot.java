package com.smp.marketplace.escrow;

/**
 * Satu slot escrow: sebuah stack item asli yang sedang dipegang mod (custody).
 *
 * <p>Ini adalah <b>sumber kebenaran item</b> (lihat CLAUDE.md). Stack aslinya —
 * lengkap dengan NBT (enchant, durability, custom name) — disimpan sebagai
 * {@link #nbtBase64}: NBT ter-kompres lalu di-encode base64, supaya bisa
 * dikembalikan utuh persis seperti saat disetor.
 *
 * <p>POJO dengan field publik agar gampang di-(de)serialisasi Gson, sama seperti
 * {@link com.smp.marketplace.config.ModConfig}.
 */
public class EscrowSlot {
    /** ID unik slot (UUID). Dipakai Node sebagai <code>escrowRef</code>. */
    public String escrowRef;

    /** UUID pemilik saat ini (yang berhak menarik item ini). */
    public String ownerUuid;

    /** Nama pemilik (cache untuk tampilan/log). */
    public String ownerName;

    /** id item kanonik, mis. "minecraft:elytra" (untuk metadata/tampilan). */
    public String itemKey;

    /** Nama tampilan item, mis. "Elytra". */
    public String itemLabel;

    /** Jumlah item di slot ini. */
    public int quantity;

    /** NBT stack asli, ter-kompres + base64. Kebenaran item ada di sini. */
    public String nbtBase64;

    /** Waktu setor (epoch ms). */
    public long createdAt;

    /** Konstruktor kosong wajib untuk Gson. */
    public EscrowSlot() {}

    public EscrowSlot(
            String escrowRef,
            String ownerUuid,
            String ownerName,
            String itemKey,
            String itemLabel,
            int quantity,
            String nbtBase64,
            long createdAt) {
        this.escrowRef = escrowRef;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.itemKey = itemKey;
        this.itemLabel = itemLabel;
        this.quantity = quantity;
        this.nbtBase64 = nbtBase64;
        this.createdAt = createdAt;
    }
}
