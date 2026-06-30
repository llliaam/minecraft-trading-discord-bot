# Minecraft Marketplace Discord Bot

Bot Discord untuk memanajemen jual-beli item Minecraft di server SMP kecil (server pertemanan).
**Bot TIDAK terhubung ke Minecraft** — ia hanya mediator informasi, matchmaking, dan notifikasi.
Pertukaran item & pembayaran tetap dilakukan manual oleh pemain di dalam game.

---

## Stack

| Komponen   | Pilihan                          |
|------------|----------------------------------|
| Bahasa     | Node.js (v22)                    |
| Library    | discord.js v14                   |
| ORM        | Prisma                           |
| Database   | Neon PostgreSQL (free tier)      |
| Scheduler  | node-cron (Fase 2)               |
| Deploy     | GitHub → Railway/Fly.io          |

---

## Keputusan Desain (FINAL)

| Aspek          | Keputusan                  | Konsekuensi                                                                 |
|----------------|----------------------------|----------------------------------------------------------------------------|
| Notifikasi     | **Ping di channel saja**   | Notif = `@user` mention di channel marketplace. Tidak handle DM diblok.     |
| Beli/Nego      | **Buy Now + Offer**        | Listing punya harga (Buy Now) DAN bisa terima offer nego.                   |
| Trust/Rating   | **Tidak ada**              | Server pertemanan, saling percaya. Data model ramping.                      |
| Mata uang      | **Teks bebas**             | `price` = kolom TEKS. Tidak bisa sortir/filter by harga.                    |

### ⚠️ Konsekuensi penting "harga teks bebas"
Karena harga adalah teks bebas (mis. `"2 stack diamond block"`), bot **tidak bisa** sortir
termurah/termahal, filter `harga < X`, atau bandingkan offer otomatis.
**Search/browse hanya filter by nama item & tipe (SELL/BUY).** Harga dinilai manual oleh manusia.
(Masa depan: bisa tambah kolom `price_numeric` opsional untuk sorting.)

---

## Arsitektur (Layered)

```
┌─────────────────────────────────────────────────┐
│                  DISCORD                          │
│  User ── Slash Commands ── Buttons/Modals/Select  │
└───────────────────────┬─────────────────────────┘
                        │ (discord.js)
┌───────────────────────▼─────────────────────────┐
│                   BOT CORE                         │
│  Command Handler / Event Handler / Interaction    │
│              ┌──────▼───────┐                     │
│              │ Service Layer │ (business logic)   │
│              └──────┬───────┘                     │
└─────────────────────┼───────────────────────────┘
                      │ (Prisma)
        ┌─────────────▼──────────────┐
        │   Neon PostgreSQL           │
        └────────────────────────────┘
```

**Pemisahan layer (WAJIB dijaga):**
- **Handler** (`commands/`, `events/`) → terima input Discord, validasi format, panggil service.
- **Service** (`services/`) → SEMUA logika bisnis (boleh buat listing? offer valid? siapa dinotif?).
- **DB** (Prisma client) → hanya simpan & ambil data.

Jangan taruh logika bisnis di handler. Jangan akses Prisma langsung dari handler.

---

## Data Model

```
Listing
 ├─ id              (PK)
 ├─ creatorId       (Discord user ID)
 ├─ type            (SELL | BUY)        ← jual barang / cari barang
 ├─ itemName        (teks)
 ├─ quantity        (Int)
 ├─ price           (TEKS bebas)        ← "64 diamond", "1 stack emerald block"
 ├─ description     (teks, opsional)
 ├─ status          (ACTIVE | PENDING | COMPLETED | CANCELLED | EXPIRED)
 ├─ messageId       (ID embed di channel, utk update tampilan)
 ├─ createdAt
 └─ expiresAt       (opsional, auto-expire — Fase 2)

Offer
 ├─ id              (PK)
 ├─ listingId       → Listing
 ├─ buyerId         (Discord user ID)
 ├─ offeredPrice    (TEKS bebas)
 ├─ message         (teks, opsional)
 ├─ status          (PENDING | ACCEPTED | REJECTED | WITHDRAWN)
 └─ createdAt

Transaction
 ├─ id              (PK)
 ├─ listingId       → Listing
 ├─ offerId         → Offer (NULL kalau Buy Now)
 ├─ sellerId / buyerId
 ├─ finalPrice      (teks)
 ├─ status          (AGREED | COMPLETED)
 └─ completedAt
```

---

## Flow Utama

```
SELL/BUY listing dibuat
   /sell item:"Elytra" qty:1 price:"64 diamond" desc:"unbreaking III"
        │
        ▼
   Embed di #marketplace + tombol [🛒 Buy Now] [💬 Make Offer]
        │
        ├──► [Buy Now] ──► konfirmasi ──► Transaction (AGREED)
        │                                 ping @seller @buyer
        │                                 listing → PENDING
        │
        └──► [Make Offer] ──► Modal (harga + pesan)
                          │
                          ▼
                    Offer (PENDING) → ping @seller
                          │   tombol [✅ Accept] [❌ Reject]
                          ▼
                    Accept ──► Transaction (AGREED)
                              offer lain → REJECTED
                              listing → PENDING
                              ping @buyer @seller
        ▼
   Trade in-game manual
        ▼
   [✔ Mark Completed] ──► Transaction COMPLETED, listing COMPLETED
                          embed berubah jadi "✅ SOLD"
```

---

## Struktur Folder

```
bot discord/
├── CLAUDE.md
├── package.json
├── .gitignore
├── .env.example
├── prisma/
│   └── schema.prisma
└── src/
    ├── index.js            ← entry point: koneksi bot, load command & event
    ├── deploy-commands.js  ← daftarkan slash commands ke Discord
    ├── lib/
    │   └── db.js           ← Prisma client singleton
    ├── commands/           ← satu file per slash command
    ├── events/             ← handler event Discord (ready, interactionCreate)
    └── services/           ← logika bisnis (listingService, offerService, dll)
```

---

## Environment Variables

```
DISCORD_TOKEN=          # token bot
CLIENT_ID=              # application/client ID
GUILD_ID=               # ID server SMP (untuk registrasi command instan saat dev)
MARKETPLACE_CHANNEL_ID= # channel tempat post listing
DATABASE_URL=           # connection string Neon PostgreSQL
```

`.env` JANGAN di-commit. Gunakan `.env.example` sebagai template.

---

## Rencana MVP

### 🟢 Fase 1 — MVP Inti (target sekarang)
1. Setup proyek: struktur, package.json, .gitignore, .env.example, Prisma + Neon, skema DB
2. `/sell` & `/buy` — buat listing → post embed dengan tombol
3. `/browse` — lihat listing aktif (paginated)
4. `/search item:` — cari listing by nama item
5. Buy Now — tombol → konfirmasi → transaksi + ping
6. Make Offer — modal → simpan → ping seller
7. Accept / Reject offer — tombol → update status + ping
8. Mark Completed — tombol → tutup transaksi, update embed
9. `/mylistings` — lihat & kelola listing sendiri
10. `/cancel` — batalkan listing sendiri
11. `deploy-commands.js` — registrasi slash commands

### 🟡 Fase 2 — Quality of Life
12. Auto-expire listing lama (node-cron)
13. `/myoffers` — lihat offer terkirim, bisa withdraw
14. Validasi & edge case (offer ke listing sendiri, double buy, listing sudah PENDING)
15. Pesan error ramah (ephemeral)

### 🔵 Fase 3 — Nice to have (opsional)
16. Edit listing
17. `/history` — riwayat transaksi
18. Kolom `price_numeric` opsional untuk sorting
19. Statistik sederhana

---

## Catatan untuk Pengembangan
- Karena notif = ping channel, semua command kerja di dalam server (guild), bukan DM.
- `messageId` listing disimpan agar embed bisa di-edit saat status berubah (mis. jadi SOLD).
- Saat dev, daftarkan command ke `GUILD_ID` (instan). Global command propagasi ~1 jam.
- Status transisi listing: ACTIVE → PENDING (ada deal) → COMPLETED. Atau ACTIVE → CANCELLED/EXPIRED.
