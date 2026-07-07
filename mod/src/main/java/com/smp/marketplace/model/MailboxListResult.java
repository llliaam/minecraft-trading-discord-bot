package com.smp.marketplace.model;

import java.util.List;

/**
 * Hasil {@code GET /mailbox} — wrapper untuk daftar item mailbox yang belum diklaim.
 */
public class MailboxListResult {
    public List<MailboxItemDto> items;
}
