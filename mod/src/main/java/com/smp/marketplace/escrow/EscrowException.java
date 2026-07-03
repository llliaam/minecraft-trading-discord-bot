package com.smp.marketplace.escrow;

/**
 * Kesalahan operasi escrow (ledger belum siap, serialisasi item gagal, dll).
 *
 * <p>Pesannya ramah-pemain sehingga GUI/command bisa langsung menampilkannya,
 * sama polanya dengan {@link com.smp.marketplace.net.ApiClient.ApiException}.
 */
public class EscrowException extends Exception {
    public EscrowException(String message) {
        super(message);
    }
}
