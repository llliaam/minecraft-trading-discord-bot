package com.smp.marketplace.model;

/**
 * Satu item mailbox dari REST bot ({@code GET /mailbox}).
 * Field publik diisi Gson via refleksi — bentuknya mengikuti {@code handlers.js}.
 */
public class MailboxItemDto {
    public int id;
    public String escrowRef;
    public String itemKey;
    public String itemLabel;
    public int quantity;
    public String reason;   // "SOLD_PAYMENT" | "CANCELLED_RETURN" | "OVERFLOW"
    public String createdAt;
}
