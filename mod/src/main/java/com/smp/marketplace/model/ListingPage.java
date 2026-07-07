package com.smp.marketplace.model;

import java.util.List;

/**
 * Hasil satu halaman <code>GET /listings</code>: daftar item + info pagination.
 * Bentuk mengikuti {@code handlers.js} sisi bot ({items,page,totalPages,total}).
 */
public class ListingPage {
    public List<ListingDto> items;
    public int page;
    public int totalPages;
    public int total;
}
