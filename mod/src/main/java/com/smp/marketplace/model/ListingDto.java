package com.smp.marketplace.model;

/**
 * Satu listing dari REST bot (<code>GET /listings</code>). Field publik diisi
 * Gson via refleksi — bentuknya mengikuti {@code handlers.js} di sisi bot.
 *
 * <p>Catatan: {@code itemName} & {@code price} masih model LAMA (teks bebas).
 * Saat Fase E, harga jadi item terstruktur (itemKey/priceItemKey) — DTO ini
 * akan menyesuaikan. Untuk browse read-only sekarang, teks sudah cukup.
 */
public class ListingDto {
    public int id;
    public String type;        // "SELL" | "BUY"
    public String itemName;
    public int quantity;
    public String price;       // teks bebas, mis. "64 diamond"
    public String description;  // boleh null
    public String creatorId;
}
