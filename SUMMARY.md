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

### ✅ E2 — Buy/bayar + swap atomik idempoten (SELESAI kode+build, 2026-07-06; BELUM diuji E2E)

**Alur beli (Fase E2):**
1. Pemain klik kiri listing SELL di `/market` GUI → buka `gui/PurchaseDepositGui.java`.
   - Slot custodial `setSlotRedirect(22, Slot(SimpleInventory(1)))` — terima pembayaran.
   - Info panel menampilkan harga listing (jenis & jumlah wajib disetor).
   - Tombol konfirmasi → validasi item (key & count), `handedOff=true`, cek /health.
   - Deposit pembayaran ke ledger (opId) → `api.purchaseListing(listingId, uuid, paymentEscrowRef, purchaseOpId)`.
   - Sukses → `ledger.withdraw(listing.escrowRef)` → `offerOrDrop` barang ke pembeli.
   - Gagal REST → `ledger.withdraw(paymentEscrowRef)` → refund pembayaran ke pembeli.
   - Guard `onClose()`: bila `!handedOff` → kembalikan pembayaran (idempoten).
2. Node `purchaseListing({listingId, buyerId, paymentEscrowRef})` (di `transactionService.js`):
   - `db.$transaction`: validasi → klaim atomik ACTIVE→COMPLETED → buat Transaction(COMPLETED) →
     buat `MailboxItem(SOLD_PAYMENT, escrowRef=paymentEscrowRef)` untuk penjual →
     return `{listing, transaction, escrowRefToRelease: listing.escrowRef}`.
3. Handler `purchaseListingFromGame` + route `POST /listings/purchase`.
   - Update embed → ✅ Selesai. Notifikasi channel: "Terjual! Pembayaran di mailbox penjual."
4. Discord "Buy Now" tombol DIHAPUS TOTAL — beli kini in-game only via escrow.
   - `ids.js`: hapus `Action.BUY_NOW/BUY_CONFIRM`.
   - `buttonRouter.js`: hapus `handleBuyNowPrompt`, `handleBuyConfirm`, import `buyNow`.
   - `embeds.js`: tombol ACTIVE hanya "Make Offer" (hapus tombol Buy Now).
   - `transactionService.js`: hapus fungsi `buyNow`.

**Node baru/ubah:**
- `services/transactionService.js`: hapus `buyNow`, tambah `purchaseListing`.
- `api/handlers.js`: tambah `purchaseListingFromGame`.
- `api/server.js`: route `POST /listings/purchase`.
- `lib/ids.js`, `interactions/buttonRouter.js`, `lib/embeds.js`: hapus Buy Now Discord.

**Mod baru/ubah:**
- `gui/PurchaseDepositGui.java` (baru): GUI setor pembayaran custodial.
- `gui/MarketGui.java`: `onInfo` listing SELL → buka `PurchaseDepositGui`.
- `net/ApiClient.java`: method `purchaseListing(id, uuid, paymentEscrowRef, opId)`.

**Verifikasi:** `node --check` semua file JS ✅, `gradlew build` sukses (smpmarket-0.1.0.jar, JDK21) ✅.
**BELUM diuji E2E manual** (perlu MC server + bot bareng).

**Skenario uji manual (TODO):**
- [ ] Klik kiri listing SELL → GUI pembayaran terbuka, info harga tampil.
- [ ] Taruh item SALAH jenis → ditolak + pesan error "Item salah!".
- [ ] Taruh item KURANG jumlah → ditolak + pesan error "Jumlah kurang!".
- [ ] Taruh item TEPAT → konfirmasi → barang pindah ke pembeli, listing COMPLETED, embed Discord update, mailbox penjual terisi.
- [ ] Tutup GUI sebelum konfirmasi → pembayaran balik ke inventory.
- [ ] Bot mati saat proses → pesan "bot tidak bisa dihubungi", pembayaran balik.
- [ ] Dua pembeli klik bersamaan → salah satu dapat "listing baru saja diambil orang lain".
- [ ] Klik kiri listing BUY → info teks (bukan GUI beli).

### ✅ E3 — Mailbox + claim in-game (SELESAI kode+build, 2026-07-06; BELUM diuji E2E)

**Alur claim:**
1. Pemain jalankan `/myclaim` → buka `gui/MailboxGui.java` (GUI 6-baris).
2. GUI load `GET /mailbox?minecraftUuid=...` → tampilkan item menunggu (CHEST per item).
3. Klik kiri item → thread: `POST /mailbox/claim` (Node tandai `claimedAt`, return `escrowRef`)
   → main thread: `ledger.withdraw(escrowRef, opId)` → `offerOrDrop` ke inventory.
4. Inventory penuh → item jatuh ke lantai (bukan mailbox baru — sederhana, tak ada rekursi).
5. Sukses/gagal → reload GUI agar daftar segar.

**Anti-dupe:** Node set `claimedAt` SEBELUM return escrowRef → double-claim ditolak
(`BusinessError`). Crash setelah claim tapi sebelum withdraw → slot tetap di ledger
(admin recover manual). Withdraw idempoten via opId baru per klaim.

**Node baru/ubah:**
- `services/mailboxService.js`: tambah `claimMailboxItem({mailboxId, ownerId})`.
- `api/handlers.js`: tambah `mailboxList` (GET /mailbox) + `mailboxClaim` (POST /mailbox/claim).
- `api/server.js`: 2 route baru didaftarkan.

**Mod baru/ubah:**
- `model/MailboxItemDto.java` (baru): DTO satu item mailbox dari REST.
- `model/MailboxListResult.java` (baru): wrapper list `GET /mailbox`.
- `net/ApiClient.java`: method `fetchMailbox(uuid)` + `claimMailboxItem(id, uuid)`.
- `gui/MailboxGui.java` (baru): GUI chest klaim mailbox, render per-item + load/reload async.
- `command/ClaimCommand.java` (baru): `/myclaim` → buka MailboxGui.
- `SmpMarketMod.java`: daftarkan `ClaimCommand`.

**Verifikasi:** `node --check` semua file JS ✅, `gradlew build` sukses ✅.
**BELUM diuji E2E manual** (perlu MC server + bot bareng).

**Skenario uji manual (TODO):**
- [ ] `/myclaim` → GUI terbuka, tampilkan item mailbox (SOLD_PAYMENT + CANCELLED_RETURN).
- [ ] Klik kiri item → item pindah ke inventory, GUI reload tanpa item itu.
- [ ] `/myclaim` saat mailbox kosong → BARRIER "Mailbox kosong."
- [ ] Inventory penuh → item jatuh ke lantai, pesan sukses tetap muncul.
- [ ] Bot mati saat klaim → pesan error, item tetap di mailbox (bisa diklaim ulang).
- [ ] Klaim dua kali item sama (double-click cepat) → klaim kedua ditolak.

### ⏳ E4 — Reservasi 24 jam + expiry sweeper + rekonsiliasi startup (BELUM)

## GOTCHA
- Build mod: WAJIB JDK 21 (`org.gradle.java.home` + JAVA_HOME=jdk-21).
- Prisma Windows: hentikan bot sebelum db push/generate (engine file terkunci).
- `apiBase` mod = 127.0.0.1 → bot & server MC di komputer sama.
- sgui 1.6.1: `ClickType.isLeft/isRight` = FIELD bukan method; `AnvilInputGui` ada.
