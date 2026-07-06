## Status Implementasi
- **Command Discord (10):** /sell /buy /browse /search /offer /cancel /mylistings /link
  /unlink /whoami. Aksi tombol: Buy Now, Accept/Reject offer, Mark Completed. Make Offer =
  slash `/offer` (tombol di embed mengarahkan ke sana). Semua interaksi pakai defer
  (anti timeout 3 detik Discord).
- **✅ Fase A SELESAI** — DB dilokalkan ke SQLite (`file:./data.db`), start.bat Pola 3 dibuat.
- **✅ Fase B SELESAI** — linking akun: model PlayerLink/LinkCode, `linkService.js`,
  command /link /unlink /whoami.
- **✅ Fase C1 SELESAI** — REST server Node (`src/api/`, node:http tanpa Express, 
  127.0.0.1, auth Bearer). Endpoint: GET /health, GET /listings, POST /link/redeem.
- **✅ Fase C2 SELESAI PENUH** — mod Fabric di `mod/` (Gradle, Java 21, sgui ter-bundle).
  - `/link <kode>` → REST bot → tautkan akun (**pipa TULIS** Mod→Bot terbukti).
  - `/market` → GUI chest sgui browse listing read-only, pagination prev/next
    (**pipa BACA** Bot→Mod terbukti). Sudah dites tampil listing asli + navigasi halaman.
  - Config mod di `config/smpmarket.json` (apiBase+apiSecret, secret di luar git).
- **✅ MIGRASI MODEL ITEM SELESAI** (2026-07-01, sebelum Fase D) — harga teks bebas →
  terstruktur (itemKey/itemLabel/priceItemKey/priceQuantity). Katalog item
  `src/lib/itemCatalog.js` (~130 item MC 1.21.1 — kini diperluas jadi 1332 item lewat
  dump registry, lihat entri "REVISI UX OFFER + KATALOG PENUH" di bawah) + autocomplete
  Discord di /sell /buy (opsi item & price_item + price_qty). Schema + semua
  service/embed/handler/DTO mod ikut.
  Field Fase E (escrowRef, reservedFor/Until, status RESERVED) sudah ditambah (nullable)
  → tak perlu migrasi lagi. Sudah dites Discord + in-game (harga tampil "64× Diamond").
  - **Make Offer via `/offer` (BUKAN modal).** Modal Discord tak mendukung autocomplete,
    jadi item pembayaran akan melenceng dari katalog. Solusi: slash command `/offer`
    (id + price_item autocomplete + price_qty + message) — item dijamin kanonik,
    konsisten dgn /sell /buy. Tombol "Make Offer" di embed kini cuma **mengarahkan**
    (reply ephemeral berisi `/offer id:<n> ...` siap-pakai). `modalRouter.js` dikosongkan
    (tak ada modal aktif). **ATURAN: modal Discord tak boleh dipakai untuk input item.**
- **✅ Fase D SELESAI (kode, 2026-07-02)** — tulis dari in-game (non-item, non-escrow):
  - **Node:** `startApiServer(client)` kini menerima `client` Discord (dialirkan dari
    `src/index.js`) agar handler REST bisa post embed/kirim pesan seperti handler Discord.
    `offerService.js` +`getOffersForCreator(creatorId)`. `handlers.js` +4 handler:
    `createBuyListing`, `createOfferFromGame`, `myOffers`, `respondOffer` (semua resolve
    `minecraftUuid`→`discordId` via `linkService.getLinkByMc`, lempar `BusinessError` ramah
    bila belum `/link`). Route baru (semua butuh auth Bearer): `POST /listings/buy`,
    `POST /offers`, `GET /offers/mine`, `POST /offers/respond`.
  - **Mod:** DTO `OfferDto`/`OfferListResult`. `ApiClient` +4 metode
    (`createBuyListing`/`createOffer`/`fetchMyOffers`/`respondOffer`) — pola `send()`/
    `ApiException` konsisten dgn metode lama. Command baru: `/marketbuy <quantity>
    <price_qty> [description]` (item dicari = main hand, item bayar = **off-hand**, tombol
    F — satu2nya cara pegang dua item sekaligus), `/offer <listingId> <price_qty>
    [message]` (item bayar = main hand saja, listing target sudah diketahui via ID),
    `/myoffers` (buka GUI baru). GUI `MyOffersGui.java` (mirror `MarketGui`, chest 9x6):
    klik kiri = Accept, klik kanan = Reject (`ClickType.isRight`/`isLeft` — **field**,
    bukan method, di `sgui` 1.6.1), auto-reload list stlh aksi. Semua tiga command baru
    didaftarkan di `SmpMarketMod.java`.
  - Semua pemilihan item pakai **held item** (main/off-hand → `Registries.ITEM.getId`),
    tak ada input teks bebas utk item — konsisten dgn aturan "modal Discord tak boleh
    dipakai utk item" tapi versi in-game.
  - **Diverifikasi:** `mod` build sukses (`gradlew build`, JDK 21, no error/warning),
    `handlers.js`/`server.js` lolos `node --check`, semua import/export Node dicocokkan.
  - **BELUM diuji end-to-end manual** (perlu server MC + bot jalan bareng): alur
    marketbuy→embed Discord, offer→embed+ping+`/myoffers`, accept→"Deal!"+update embed,
    reject→notif buyer, error path (belum linked, tangan kosong).
- **✅ REVISI UX OFFER + KATALOG PENUH (kode, 2026-07-02)** — hasil keluhan flow `/offer`:
  - **Offer in-game pindah ke GUI `/market`.** Command `/offer` DIHAPUS
    (`OfferCommand.java` + registrasi di `SmpMarketMod.java`). Di `MarketGui`, tiap
    listing kini interaktif: **klik kiri = info** (beli langsung ditunda ke Fase E,
    tampil pesan), **klik kanan = buka `OfferInputGui`**.
  - **`OfferInputGui.java` (baru), 2 langkah:** (1) GUI menampilkan **item unik dari
    inventory pemain** sebagai tombol → pemain klik untuk pilih JENIS item bayar;
    (2) **`SignGui`** terbuka → pemain ketik JUMLAH di baris atas (ala DonutSMP).
    Lalu panggil `api.createOffer(...)` (endpoint & method LAMA, dipakai ulang).
  - **⚠️ DEVIASI dari rencana awal (anti-dupe):** pemain TIDAK menaruh item fisik ke
    GUI. GUI cuma **membaca jenis** item dari inventory (klik = baca `Registries.ITEM
    .getId`), item tak pernah keluar dari inventory. Nol kustodi = nol jendela
    crash/dupe. Konsisten "offer TIDAK setor barang". (Rencana semula "taruh item lalu
    kembalikan" ditinggalkan karena ada jendela kustodi yang tak perlu.)
  - **Katalog jadi SEMUA item MC** (bukan cuma "barang berharga"):
    - Mod: command admin **`/smpmarket dumpitems`** (`DumpItemsCommand.java`, perm lvl 2)
      loop `Registries.ITEM` → tulis `{key,label}` ke `smpmarket-items.json` di working
      dir server.
    - Node: `itemCatalog.js` kini **muat `src/lib/smpmarket-items.json`** bila ada
      (dua-lapis: dump JSON dulu, fallback ke RAW ~130 item bila file belum ada). Semua
      export (`findItems`/`isValidKey`/`prettify`/`formatPrice`) tetap → autocomplete
      Discord otomatis dapat semua item tanpa ubah handler.
    - Node: `+isValidKeyFormat(key)` (regex `namespace:path`). Jalur IN-GAME di
      `handlers.js` (`createBuyListing`, `createOfferFromGame`) kini validasi FORMAT saja
      (item dari registry asli), BUKAN keanggotaan katalog. Jalur Discord (`offer.js`,
      `_listingShared.js`) tetap pakai `isValidKey` (katalog).
  - **✅ AKTIF (2026-07-02):** `/smpmarket dumpitems` sudah dijalankan; `smpmarket-items.json`
    (**1332 item**) sudah disalin ke `src/lib/`. Autocomplete Discord kini mengenal semua
    item MC 1.21.1, konsisten dgn in-game. **Cara update ke depan:** jalankan dumpitems
    lagi → salin `smpmarket-items.json` ke `src/lib/` (timpa yg lama) → restart bot.
    Nama file dibaca apa adanya, tak perlu rename.
  - **Diverifikasi:** `mod` build sukses (`gradlew build`, JDK 21), Node `node --check`
    lolos. Juga menghapus method mati `renderEmpty()` di `MyOffersGui.java` (dead code
    ber-error kompilasi, bertanda "abal2, hapus nanti"). **BELUM diuji end-to-end manual.**
- **✅ Fase E1 SELESAI (kode+build, 2026-07-03)** — ledger escrow + deposit SELL:
  - **Mod:** `escrow/EscrowLedger.java` (singleton, inti anti-dupe: `ItemStack`→NBT base64,
    persist atomik temp+`ATOMIC_MOVE` tiap mutasi, idempotensi via map `opId`, load saat
    `SERVER_STARTED`, backup file rusak). Ledger = `smpmarket-escrow.json` di `getGameDir()`.
    `escrow/EscrowSlot.java`, `escrow/EscrowException.java`. Hook lifecycle di `SmpMarketMod`
    (STARTED→load, STOPPING→flush). `command/SellCommand.java` (`/marketsell <price_qty>
    [desc]`), `ApiClient.createSellListing(...)`.
  - **Node:** `listingService.createListing` terima `escrowRef`; handler `createSellListing`;
    route `POST /listings/sell`.
  - **Diverifikasi:** `gradlew build` sukses (jar `smpmarket-0.1.0.jar`), `node --check` lolos.
    **Teruji manual sebagian oleh user:** setor barang → item hilang dari inventory, ledger.json
    muncul, embed SELL tampil, item persist lintas restart (garansi anti-crash ✅).
  - **GOTCHA NBT (1.21.1 yarn build.3):** `NbtSizeTracker.ofUnlimited()` TIDAK ADA — pakai
    `NbtSizeTracker.ofUnlimitedBytes()`. Encode: `stack.encode(registries)`→`NbtIo.writeCompressed`
    →base64. Decode: base64→`NbtIo.readCompressed(in, ofUnlimitedBytes())`→`ItemStack.fromNbt(
    registries, compound).orElse(EMPTY)`. `registries = server.getRegistryManager()`.
  - **⚠️ `/marketsell` held-item AKAN DIGANTI GUI** (lihat Keputusan Desain: "REVISI SELL").
- **✅ REVISI SELL GUI + /cancel refund SELESAI (kode+build, 2026-07-03)** — sekali garap:
  - **Mod GUI:** `gui/SellDepositGui.java` (langkah 1 — slot custodial setor barang) +
    `gui/PaymentPickerGui.java` (langkah 2 — picker katalog PENUH + search anvil + sign jumlah).
    - `SellDepositGui`: satu slot input `setSlotRedirect(22, new Slot(SimpleInventory(1),...))`.
      Tombol konfirmasi → ambil barang dari slot ke variabel lokal + `handedOff=true` (hilangkan
      balapan onClose selama async), cek `/health`, deposit ledger (opId), buka picker. Guard
      `onClose()`: bila `!handedOff` → `returnInput()` (offerOrDrop, idempoten via clear).
    - `PaymentPickerGui`: katalog `Registries.ITEM` (cache statis, non-AIR, sorted). Paginasi
      45/hlm. Search = **`AnvilInputGui`** (ADA di sgui 1.6.1 — filter live via override `onInput`;
      di sini pakai tombol "Terapkan" di slot 2 + `getInput()`). Klik item → `SignGui` ketik
      jumlah → `api.createSellListing`. **Nasib escrow diputus di SATU tempat:** refund HANYA saat
      picker ditutup (`!navigating && !settled`); pindah ke search/sign set `navigating=true`
      (escrow utuh, selalu balik ke picker). `settled` jamin commit/refund sekali.
    - `command/SellCommand.java` DITULIS ULANG: `/marketsell` (tanpa arg) → buka `SellDepositGui`.
      Held-item main/off-hand DIPENSIUNKAN.
  - **Mod cancel:** `command/MarketCancelCommand.java` (`/marketcancel <listingId>`) → REST
    `cancelListing` → dapat `escrowRef` → `ledger.withdraw(opId)` → `offerOrDrop`. `escrowRef=null`
    (mis. BUY) → cukup konfirmasi. `ApiClient.checkHealth()` (GET /health, bool) +
    `ApiClient.cancelListing(listingId,uuid)→escrowRef|null`. Registrasi di `SmpMarketMod`.
  - **Node:** `mailboxService.js` (`depositToMailbox`/`getUnclaimedMailbox`).
    `listingService.cancelListing` kini terima `returnMode` ("ingame"|"mailbox", default mailbox):
    SELL+ingame → kembalikan `escrowRef` (mod tarik fisik); SELL+mailbox (Discord/offline) →
    buat `MailboxItem` (CANCELLED_RETURN, escrowRef sama, **barang tetap di escrow** — withdraw
    saat /claim). Handler `cancelListingFromGame` + route `POST /listings/cancel`. Discord
    `/cancel` kini `returnMode:"mailbox"` + info "barang menunggu di mailbox". Schema: model
    `MailboxItem` + enum `MailboxReason` (db push + generate ✅).
  - **⚠️ Insight Discord-cancel = MURNI metadata Node.** Barang sudah di escrow; cancel offline
    hanya ubah "label" listing→mailbox (escrowRef sama). Mod TAK dipanggil sama sekali. Withdraw
    fisik ditunda ke /claim (E3). Jadi mailbox E3 tertarik sebagian, tapi ringan.
  - **Pre-flight /health:** `SellDepositGui` cek `api.checkHealth()` SEBELUM deposit → hindari
    barang orphan di escrow saat bot mati (listing tak bisa dibuat). Refund tetap backstop.
  - **Diverifikasi:** `gradlew build` sukses (jar `smpmarket-0.1.0.jar`, JDK 21), semua Node
    `node --check` lolos, `prisma validate/db push/generate` sukses. **BELUM diuji end-to-end
    manual** (perlu server MC + bot bareng): setor via GUI, search anvil, batal di tiap titik
    (deposit gui close / picker close / sign kosong / REST gagal) → barang balik; /marketcancel →
    barang balik; Discord /cancel SELL → MailboxItem dibuat.
  - **GOTCHA (samping):** `MyOffersGui.java:86` sempat korup (`itemL   el`) dari edit sebelumnya —
    diperbaiki jadi `itemLabel` saat build.
- **Berikutnya:** **E2** (buy/bayar + swap atomik idempoten) → **E3** (mailbox+claim penuh:
  `/claim` in-game tarik dari escrow → inventory, overflow balik mailbox) → **E4** (reservasi 24j +
  expiry sweeper + rekonsiliasi startup) → **F** (sinkron penuh + WS notif).