# FASE E — Escrow & Settlement — Progress & Alur Kerja

Dokumen khusus Fase E (bagian anti-dupe sistem marketplace). Sumber kebenaran
arsitektur tetap CLAUDE.md; ini ringkasan progres + alur kerja Fase E saja.

## Aturan Anti-Dupe (NON-NEGOTIABLE)
1. Mod SATU-SATUNYA pemindah item. Node tak pernah menyentuh ItemStack.
2. Tiap operasi pindah item punya opId unik & idempoten.
3. Ledger escrow persist ke disk tiap mutasi (atomic temp + move).
4. Startup: ledger mod = sumber kebenaran item.

## Sub-Fase

### ✅ E1 — Ledger escrow + deposit SELL (SELESAI kode+build, teruji manual sebagian)
- `escrow/EscrowLedger.java` (singleton): deposit/withdraw idempoten via opId,
  persist atomik tiap mutasi, load saat SERVER_STARTED, backup file rusak.
  Ledger = `smpmarket-escrow.json` di getGameDir().
- `escrow/EscrowSlot.java`, `escrow/EscrowException.java`.
- Deposit awal via `/marketsell` held-item (main+offhand) — SUDAH DIPENSIUNKAN.
- GOTCHA NBT: `NbtSizeTracker.ofUnlimitedBytes()` (bukan `ofUnlimited()`).

### ✅ REVISI SELL GUI + /cancel refund (SELESAI kode+build, 2026-07-03; BELUM diuji E2E)
Menggantikan `/marketsell` held-item dengan alur GUI, + `/cancel` mengembalikan barang.

**Alur SELL baru (2 langkah GUI):**
1. `/marketsell` (tanpa argumen) → buka `gui/SellDepositGui.java`.
   - Slot custodial `setSlotRedirect(22, new Slot(SimpleInventory(1),0,0,0))`.
   - Tombol konfirmasi → ambil barang ke var lokal + `handedOff=true`, cek
     `/health`, deposit ke ledger (opId), buka picker.
   - Guard `onClose()`: bila `!handedOff` → kembalikan barang (offerOrDrop).
2. `gui/PaymentPickerGui.java` — pilih item pembayaran + jumlah.
   - Katalog PENUH `Registries.ITEM` (cache statis, non-AIR, sorted), paginasi 45/hlm.
   - Search = `AnvilInputGui` (ADA di sgui 1.6.1) — filter live by id+nama.
   - Klik item → `SignGui` ketik JUMLAH → `api.createSellListing`.
   - Refund HANYA saat picker ditutup (`!navigating && !settled`); pindah ke
     search/sign set `navigating=true` (escrow utuh). `settled` = commit/refund sekali.

**Alur CANCEL:**
- In-game `command/MarketCancelCommand.java` (`/marketcancel <listingId>`):
  REST cancel (returnMode="ingame") → dapat escrowRef → `ledger.withdraw(opId)` →
  offerOrDrop. escrowRef=null (mis. BUY) → cukup konfirmasi.
- Discord `/cancel` (returnMode="mailbox", pemain bisa offline): barang TETAP di
  escrow, dibuat `MailboxItem` (CANCELLED_RETURN, escrowRef sama). MOD TAK DIPANGGIL.
  Withdraw fisik ditunda ke `/claim` (E3). = murni metadata Node.

**Node baru/ubah:**
- `services/mailboxService.js` (depositToMailbox/getUnclaimedMailbox).
- `services/listingService.js`: `cancelListing({listingId, actorId, returnMode})`
  → `{listing, escrowRef}`.
- `api/handlers.js`: `cancelListingFromGame` + route `POST /listings/cancel`.
- `api/server.js`: route terdaftar.
- `commands/cancel.js`: returnMode "mailbox" + info mailbox.
- `net/ApiClient.java`: `checkHealth()`, `cancelListing(id,uuid)→escrowRef|null`.
- Schema: model `MailboxItem` + enum `MailboxReason` (db push + generate ✅).

**Pre-flight /health:** SellDepositGui cek `api.checkHealth()` SEBELUM deposit →
hindari barang orphan saat bot mati. Refund tetap backstop.

**Verifikasi:** gradlew build sukses (jar smpmarket-0.1.0.jar, JDK21), node --check
lolos, prisma validate/db push/generate sukses. **BELUM diuji E2E manual.**

**Skenario uji manual (TODO, perlu MC server + bot bareng):**
- [ ] `/marketsell` → taruh barang → konfirmasi → picker → search anvil → pilih →
      sign jumlah → listing ACTIVE + embed Discord + item di ledger.
- [ ] Batal di tiap titik → barang balik: tutup SellDepositGui sebelum konfirmasi;
      tutup picker; sign kosong (balik picker lalu tutup); REST gagal.
- [ ] `/marketcancel <id>` (online) → barang balik ke inventory.
- [ ] Discord `/cancel` SELL → MailboxItem CANCELLED_RETURN dibuat, barang tetap
      di escrow, embed jadi Dibatalkan.
- [ ] Bot mati saat konfirmasi setor → pesan "bot tidak bisa dihubungi", barang balik.

### ⏳ E2 — Buy/bayar + swap atomik idempoten (BELUM)
Pembeli setor pembayaran → swap barang↔bayar atomik.

### ⏳ E3 — Mailbox + claim penuh (BELUM, sebagian ditarik oleh /cancel)
`/claim` in-game tarik dari escrow → inventory; overflow balik mailbox.

### ⏳ E4 — Reservasi 24 jam + expiry sweeper + rekonsiliasi startup (BELUM)

## GOTCHA
- Build mod: WAJIB JDK 21 (`org.gradle.java.home` + JAVA_HOME=jdk-21).
- Prisma Windows: hentikan bot sebelum db push/generate (engine file terkunci).
- `apiBase` mod = 127.0.0.1 → bot & server MC di komputer sama.
- sgui 1.6.1: `ClickType.isLeft/isRight` = FIELD bukan method; `AnvilInputGui` ada.
