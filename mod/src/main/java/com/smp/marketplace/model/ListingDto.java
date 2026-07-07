package com.smp.marketplace.model;

/**
 * Satu listing dari REST bot (<code>GET /listings</code>). Field publik diisi
 * Gson via refleksi — bentuknya mengikuti {@code handlers.js} di sisi bot.
 *
 * <p>Harga terstruktur (item + jumlah). Bot mengirim <code>priceText</code>
 * yang SUDAH diformat (mis. "64× Diamond") agar mod tak perlu menduplikasi
 * katalog item. Field mentah ({@code priceItemKey}/{@code priceQuantity}) ikut
 * dikirim untuk kebutuhan Fase E (pencocokan escrow) nanti.
 */
public class ListingDto {
    public int id;
    public String type;          // "SELL" | "BUY"
    public String itemKey;       // id kanonik MC, mis. "minecraft:elytra"
    public String itemLabel;     // teks tampilan, mis. "Elytra"
    public int quantity;
    public String priceItemKey;  // id item pembayaran (mentah)
    public int priceQuantity;    // jumlah item pembayaran (mentah)
    public String priceText;     // harga siap-tampil, mis. "64× Diamond"
    public String description;   // boleh null
    public String creatorId;
    public String creatorName;   // minecraftName penjual (dari PlayerLink), bisa null
    public String status;        // "ACTIVE" | "RESERVED" | "PENDING" | dll
    public String escrowRef;     // ada di /listings/mine, null untuk BUY
    public String reservedFor;   // Discord user ID pembeli (saat RESERVED), bisa null
    public String reservedUntil; // ISO 8601 batas waktu reservasi, bisa null
}
