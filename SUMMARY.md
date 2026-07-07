# Progress Fase E — Escrow & Settlement

Sumber kebenaran arsitektur: CLAUDE.md. Dokumen ini ringkasan progres implementasi saja.

## Aturan Anti-Dupe (NON-NEGOTIABLE)
1. Mod SATU-SATUNYA pemindah item. Node tak pernah menyentuh ItemStack.
2. Tiap operasi punya opId unik & idempoten.
3. Ledger persist atomik tiap mutasi (`smpmarket-escrow.json`).
4. Startup: ledger mod = sumber kebenaran item.

## Status Sub-Fase

| Sub-fase | Status | Catatan |
|----------|--------|---------|
| E1 — Ledger + deposit SELL | ✅ kode+build | teruji manual sebagian |
| Revisi SELL GUI + /cancel | ✅ kode+build | BELUM diuji E2E |
| E2 — Buy/swap atomik | ✅ kode+build | BELUM diuji E2E |
| E3 — Mailbox + /myclaim | ✅ kode+build | BELUM diuji E2E |
| Bug fixes + fitur tambahan | ✅ kode+build | BELUM diuji E2E |
| E4 — Reservasi 24j + sweeper | ✅ kode+build | BELUM diuji E2E |

---

## ✅ E1 — Ledger escrow + deposit SELL
- `EscrowLedger.java`: deposit/withdraw idempoten via opId, persist atomik, load saat `SERVER_STARTED`.
- GOTCHA NBT: `NbtSizeTracker.ofUnlimitedBytes()`.

## ✅ Revisi SELL GUI + /cancel
**SELL:** `/marketsell` → `SellDepositGui` (slot custodial, guard `onClose`, pre-flight `/health`) → `PaymentPickerGui` (katalog penuh `Registries.ITEM`, paginasi, search `AnvilInputGui`, sign jumlah) → `api.createSellListing`.

**Cancel:**
- In-game `/marketcancel <id>` → returnMode `"ingame"` → Node kembalikan `escrowRef` → mod `withdraw` → `offerOrDrop`.
- Discord `/cancel` → returnMode `"mailbox"` → barang tetap di escrow, buat `MailboxItem(CANCELLED_RETURN)`. Withdraw fisik saat `/myclaim`.

**Node:** `mailboxService.js`, `cancelListing({returnMode})`, `POST /listings/cancel`. Schema: `MailboxItem` + `MailboxReason`.

## ✅ E2 — Buy/bayar + swap atomik
**Alur:** Klik kiri listing SELL di `/market` → `PurchaseDepositGui` (slot custodial, validasi item+jumlah **TEPAT**, `handedOff`, pre-flight) → deposit bayar ke ledger → `POST /listings/purchase` → sukses: `withdraw(listing.escrowRef)` → barang ke pembeli; gagal: `withdraw(paymentEscrowRef)` → refund.

**Node:** `purchaseListing` di `transactionService.js` — klaim atomik `ACTIVE→COMPLETED`, buat `Transaction`, buat `MailboxItem(SOLD_PAYMENT)` untuk penjual. Discord Buy Now **dihapus total**.

**Mod:** `PurchaseDepositGui.java`, wiring `MarketGui.onInfo` → buka PurchaseDepositGui.

## ✅ E3 — Mailbox + claim in-game
**Alur:** `/myclaim` → `MailboxGui` (GUI 6-baris) → load `GET /mailbox` → klik kiri item → `POST /mailbox/claim` (Node tandai `claimedAt`, return `escrowRef`) → `ledger.withdraw` → `offerOrDrop`. Inventory penuh → item jatuh ke lantai (bukan overflow-ke-mailbox-baru).

**Node:** `claimMailboxItem`, handler `mailboxList` + `mailboxClaim`. **Mod:** `MailboxItemDto`, `MailboxListResult`, `MailboxGui`, `ClaimCommand` (`/myclaim`).

## ✅ Bug Fixes + Fitur Tambahan (2026-07-06)

### Fix: validasi jumlah pembayaran harus TEPAT
`PurchaseDepositGui.java`: ubah `< priceQuantity` → `!= priceQuantity`. Kelebihan item ditolak.

### Fix: Discord /cancel cek online status player
- `src/lib/onlinePlayers.js` — in-memory Set UUID online.
- Mod register `ServerPlayConnectionEvents.JOIN/DISCONNECT` → `POST/DELETE /players/online`.
- `cancel.js` — cek `isOnline(uuid)`: online → instruksikan `/marketcancel <id>` in-game; offline → mailbox.

### Fitur: `/mylisting` in-game
- `GET /listings/mine` — listing ACTIVE/PENDING milik pemain.
- `MyListingGui.java` — GUI daftar listing sendiri, klik kiri = cancel (barang balik ke inventory).
- `MyListingCommand.java` — `/mylisting`.

### Fitur: nama MC penjual di marketplace
- `listingService.js` — `attachCreatorNames()`: batch join `PlayerLink`, tambah field `creatorName`.
- `embeds.js` — embed + `/browse` tampilkan `"NamaMC (<@discordId>)"`.
- `MarketGui.java` — lore listing tampilkan `"Penjual: NamaMC"`.

---

## ✅ E4 — Reservasi 24 jam + Sweeper + Reconciliation (kode+build, 2026-07-07)

### Perubahan Node.js
- **`offerService.acceptOffer`**: listing SELL → status `RESERVED` (bukan PENDING), set `reservedFor=buyerId`, `reservedUntil=now+24jam`. Listing BUY tetap `PENDING` (tidak ada barang escrow yang perlu dijaga).
- **`transactionService.purchaseListing`**: terima status `ACTIVE` dan `RESERVED`. Validasi tambahan untuk RESERVED: `buyerId` harus sama `reservedFor`, dan `reservedUntil` belum expired.
- **`listingService.cancelListing`**: izinkan cancel dari `RESERVED` (reservasi belum dibayar = boleh batal). Update filter `updateMany` ke `status: { in: ["ACTIVE", "RESERVED"] }`.
- **`listingService.browseListings` + `searchListings`**: include `RESERVED` di filter (listing RESERVED masih tampil di marketplace).
- **`services/sweeper.js`** (BARU): `expireReservations()` — query + update batch listing RESERVED expired → ACTIVE; `runSweep(client)` — expire + notif Discord; `startSweeper(client)` — startup reconciliation + setInterval 5 menit.
- **`events/ready.js`**: panggil `startSweeper(client)` saat bot ready.
- **`lib/embeds.js`**: `buildListingEmbed` tambah field "Direservasi untuk @user" dan "Batas bayar <t:unix:R>" saat status RESERVED.
- **`api/handlers.js`** (`GET /listings`): ikut kirim field `status`, `reservedFor`, `reservedUntil` ke mod.

### Perubahan Mod Java
- **`model/ListingDto.java`**: tambah field `reservedFor` dan `reservedUntil`.
- **`gui/MarketGui.java`**: listing RESERVED tampil badge `[JUAL]` berwarna ungu, lore "🔒 Direservasi", klik diblok + pesan merah.

### Diverifikasi
- `gradlew build` sukses (JDK 21), `node --check` lolos semua file.
- **BELUM diuji E2E manual** (perlu MC server + bot bersamaan).

---

## TODO Uji Manual (perlu MC server + bot berjalan bersamaan)
- [ ] SELL: `/marketsell` → setor → picker → sign jumlah → listing aktif di Discord.
- [ ] Batal di tiap titik SELL → barang balik ke inventory.
- [ ] Beli: klik listing SELL → taruh bayar TEPAT → barang pindah, mailbox penjual terisi.
- [ ] Beli: taruh bayar salah jenis/jumlah → ditolak + refund.
- [ ] `/myclaim` → klaim item → GUI reload.
- [ ] Discord `/cancel` saat online → instruksi `/marketcancel`; saat offline → mailbox.
- [ ] `/mylisting` → tampil listing sendiri, klik kiri cancel → barang balik.
- [ ] Nama MC muncul di embed Discord dan GUI `/market`.
- [ ] Dua pembeli bersamaan → salah satu dapat "listing baru saja diambil orang lain".

## GOTCHA
- Build mod: WAJIB JDK 21 (`org.gradle.java.home` di gradle.properties).
- Prisma Windows: hentikan bot sebelum `db push/generate` (engine file terkunci).
- `apiBase` = `127.0.0.1` — bot & server MC di komputer sama.
- sgui 1.6.1: `ClickType.isLeft/isRight` = FIELD bukan method; `AnvilInputGui` tersedia.
