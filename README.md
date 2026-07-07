# SMP Marketplace

Sistem marketplace jual-beli item untuk server Minecraft SMP pertemanan.
Terdiri dari dua komponen yang bekerja sama di satu komputer:

- **Bot Discord** — etalase, ruang negosiasi, dan papan notifikasi
- **Mod Fabric** — GUI in-game, escrow item (kustodian), dan settlement atomik

Model ekonomi: **barter penuh** (harga = item apa saja + jumlah, bukan mata uang tunggal).
Barang disetor ke escrow sebelum listing aktif — tidak ada trust manual, sistem yang menjamin.

---

## Download Mod

> Mod ini **server-side only** — tidak perlu dipasang di client pemain.

[![Download latest release](https://img.shields.io/github/v/release/llliaam/minecraft-trading-discord-bot?label=Download%20Mod&color=brightgreen&style=for-the-badge)](https://github.com/llliaam/minecraft-trading-discord-bot/releases/latest)

Unduh `smpmarket-x.x.x.jar` dari halaman Releases, letakkan di folder `mods/` server Minecraft.

---

## Arsitektur

```
┌─────────────── DISCORD ────────────────┐
│  /browse /search /offer /cancel ...    │
└──────────────────┬─────────────────────┘
                   │
┌──────────────────▼─────────────────────┐
│         BOT NODE.js  — "OTAK"          │
│  Logika bisnis · SQLite · REST + WS    │
│  localhost:8765                        │
└──────────────────┬─────────────────────┘
                   │ HTTP REST + WebSocket
┌──────────────────▼─────────────────────┐
│       MOD FABRIC (Java) — "TANGAN"     │
│  GUI chest · escrow · command in-game  │
│  Satu-satunya yang boleh pindah item   │
└──────────────────┬─────────────────────┘
                   │
           [ Dunia Minecraft ]
```

Bot dan server Minecraft **wajib di komputer yang sama** — komunikasi lewat loopback (`127.0.0.1`), tidak keluar ke internet.

---

## Tech Stack

### Bot Discord (Node.js)

| Komponen | Versi |
|----------|-------|
| Node.js | v22 |
| discord.js | ^14.16.3 |
| Prisma ORM | ^6.1.0 |
| SQLite | via Prisma (`file:./data.db`) |
| ws (WebSocket) | ^8.21.0 |
| dotenv | ^16.4.7 |

### Mod Fabric (Java)

| Komponen | Versi |
|----------|-------|
| Java | 21 |
| Minecraft | 1.21.1 |
| Fabric Loader | 0.16.14 |
| Fabric API | 0.116.10+1.21.1 |
| sgui (Patbox) | 1.6.1+1.21.1 |
| Yarn Mappings | 1.21.1+build.3 |
| Gradle / Fabric Loom | 1.10-SNAPSHOT |

> `sgui` di-bundle langsung ke dalam jar mod — server tidak perlu memasang dependensi terpisah.

---

## Fitur

### In-game (Mod)
| Command | Deskripsi |
|---------|-----------|
| `/market` | Browse listing aktif (GUI chest, paginasi) |
| `/marketsell` | Buat listing SELL — setor barang via GUI escrow |
| `/marketbuy` | Buat listing BUY (wishlist) — pilih item dicari + item bayar via GUI |
| `/marketcancel <id>` | Batalkan listing sendiri — barang langsung balik ke inventory |
| `/mylisting` | Lihat listing milik sendiri, klik kiri untuk cancel |
| `/myoffers` | Lihat offer yang masuk ke listing-mu |
| `/myclaim` | Klaim item di mailbox (pembayaran hasil jual / barang cancel saat offline) |
| `/link <kode>` | Tautkan akun Minecraft ke Discord |
| `/smpmarket dumpitems` | (Admin) Dump seluruh registry item MC ke JSON |

### Discord (Bot)
| Command | Deskripsi |
|---------|-----------|
| `/browse` | Browse listing aktif |
| `/search <query>` | Cari listing berdasarkan nama item |
| `/offer <id>` | Beri penawaran harga ke listing |
| `/cancel <id>` | Batalkan listing sendiri |
| `/mylistings` | Lihat listing aktif milik sendiri |
| `/link` | Mulai proses linking akun Discord ↔ Minecraft |
| `/unlink` | Putuskan linking akun |
| `/whoami` | Lihat info akun yang tertaut |

### Sistem
- **Escrow atomik** — barang tersimpan aman di ledger mod, persist ke disk setiap mutasi
- **Mailbox** — terima pembayaran/barang meski sedang offline
- **Reservasi 24 jam** — listing terkunci untuk pembeli setelah offer diterima
- **Auto-expire 30 hari** — listing lama otomatis EXPIRED, barang SELL kembali ke mailbox
- **WS push notif** — ping in-game real-time saat offer masuk/diterima/ditolak, barang terjual
- **Anti-dupe** — setiap operasi punya ID unik & idempoten, tidak bisa jalan dua kali

---

## Setup Bot Discord

### Prasyarat
- Node.js v22+
- Bot Discord sudah dibuat di [Discord Developer Portal](https://discord.com/developers/applications)
- Intents: `GUILDS`, `GUILD_MESSAGES`

### Instalasi

```bash
# Clone repo
git clone https://github.com/llliaam/minecraft-trading-discord-bot.git
cd minecraft-trading-discord-bot

# Install dependencies
npm install

# Setup database
npm run db:push
```

### Konfigurasi

Salin `.env.example` → `.env`, lalu isi:

```env
# Discord
DISCORD_TOKEN=token_bot_dari_developer_portal
CLIENT_ID=application_client_id
GUILD_ID=id_server_discord_smp
MARKETPLACE_CHANNEL_ID=id_channel_marketplace

# Database
DATABASE_URL=file:./data.db

# IPC Bot <-> Mod
API_PORT=8765
API_SECRET=string_acak_panjang_untuk_auth
```

### Jalankan

```bash
# Deploy slash commands (sekali, atau tiap ada command baru)
npm run deploy

# Jalankan bot
npm start
```

---

## Setup Mod Fabric

### Prasyarat
- Server Minecraft **Fabric 1.21.1**
- Fabric API sudah terpasang di `mods/`
- Bot Discord sudah berjalan

### Instalasi

1. Unduh `smpmarket-x.x.x.jar` dari [Releases](https://github.com/llliaam/minecraft-trading-discord-bot/releases/latest)
2. Letakkan di folder `mods/` server Minecraft
3. Jalankan server sekali untuk generate config
4. Edit `config/smpmarket.json`:

```json
{
  "apiBase": "http://127.0.0.1:8765",
  "apiSecret": "string_acak_yang_sama_dengan_API_SECRET_di_.env"
}
```

5. Restart server

### Build dari Source (opsional)

> Butuh JDK 21. Ganti path `org.gradle.java.home` di `gradle.properties` sesuai lokasi JDK 21 di komputermu.

```bash
cd mod
./gradlew build
# Jar ada di: mod/build/libs/smpmarket-0.1.0.jar
```

---

## Auto-start (Opsional)

`start.bat` menjalankan server MC dan bot Discord sekaligus. Server MC sebagai proses utama — saat server stop, bot ikut mati.

```bat
start.bat
```

---

## Struktur Folder

```
minecraft-trading-discord-bot/
├── src/
│   ├── commands/        # Slash commands Discord
│   ├── events/          # Event handler Discord
│   ├── interactions/    # Button/select menu handlers
│   ├── services/        # Logika bisnis (listing, offer, mailbox, dll)
│   ├── api/             # REST + WebSocket server untuk mod
│   └── lib/             # Utilities, item catalog, embeds
├── prisma/
│   └── schema.prisma    # Schema database SQLite
├── .env.example
├── start.bat
└── package.json
```

> Folder `mod/` (source kode Java) tidak di-track git. Download jar-nya langsung dari Releases.

---

## Aturan Anti-Dupe (NON-NEGOTIABLE)

1. **Mod adalah satu-satunya yang boleh memindahkan item.** Bot Node.js tidak pernah menyentuh `ItemStack`.
2. **Setiap operasi item punya ID unik & idempoten** — operasi yang sama tidak akan berjalan dua kali.
3. **Ledger escrow di-persist ke disk setiap mutasi** (`smpmarket-escrow.json`) — tahan crash server.
4. **Saat startup, ledger mod = sumber kebenaran item.** Bot menyelaraskan metadata ke ledger.

---

## Lisensi

MIT
