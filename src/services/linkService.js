// Logika bisnis untuk linking akun Discord <-> Minecraft.
// Sisi Discord (/link) memanggil generateLinkCode; sisi in-game (mod, Fase C)
// nanti memanggil redeemLinkCode lewat REST. Keduanya pakai service yang sama.
import { db } from "../lib/db.js";
import { BusinessError } from "./transactionService.js";

// Masa berlaku kode linking sejak dibuat.
const CODE_TTL_MS = 10 * 60 * 1000; // 10 menit

// Karakter kode: tanpa yang ambigu (0/O, 1/I) supaya gampang diketik in-game.
const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const CODE_LENGTH = 6;

/** Bangun satu kode acak (belum tentu unik — pemanggil yang menjamin keunikan). */
function randomCode() {
  let out = "";
  for (let i = 0; i < CODE_LENGTH; i++) {
    out += CODE_ALPHABET[Math.floor(Math.random() * CODE_ALPHABET.length)];
  }
  return out;
}

/**
 * Mulai proses linking dari sisi Discord: buat (atau perbarui) kode untuk user.
 * Jika user sudah tertaut, tolak — harus /unlink dulu.
 * Satu user hanya punya satu kode aktif (kode lama ditimpa).
 *
 * @param {string} discordId
 * @returns {Promise<{code: string, expiresAt: Date}>}
 * @throws {BusinessError} jika akun sudah tertaut.
 */
export async function generateLinkCode(discordId) {
  const existing = await db.playerLink.findUnique({ where: { discordId } });
  if (existing) {
    throw new BusinessError(
      `Akun Discord-mu sudah tertaut ke Minecraft **${existing.minecraftName}**. ` +
        "Pakai `/unlink` dulu kalau mau ganti.",
    );
  }

  const expiresAt = new Date(Date.now() + CODE_TTL_MS);

  // Cari kode unik (peluang tabrakan sangat kecil; coba beberapa kali utk aman).
  let code;
  for (let attempt = 0; attempt < 5; attempt++) {
    const candidate = randomCode();
    const clash = await db.linkCode.findUnique({ where: { code: candidate } });
    if (!clash) {
      code = candidate;
      break;
    }
  }
  if (!code) throw new BusinessError("Gagal membuat kode unik, coba lagi.");

  // Upsert: ganti kode lama user (kalau ada) dengan yang baru.
  await db.linkCode.upsert({
    where: { discordId },
    create: { code, discordId, expiresAt },
    update: { code, expiresAt, createdAt: new Date() },
  });

  return { code, expiresAt };
}

/**
 * Tukar kode dari sisi in-game (dipanggil mod via REST nanti).
 * Validasi kode, lalu buat PlayerLink & hapus kode.
 *
 * @param {object} input
 * @param {string} input.code           kode yang diketik pemain in-game.
 * @param {string} input.minecraftUuid  UUID pemain (dari server MC).
 * @param {string} input.minecraftName  nama pemain (untuk tampilan).
 * @returns {Promise<{link: object}>}
 * @throws {BusinessError}
 */
export async function redeemLinkCode({ code, minecraftUuid, minecraftName }) {
  const normalized = String(code ?? "").trim().toUpperCase();
  if (!normalized) throw new BusinessError("Kode tidak boleh kosong.");

  return db.$transaction(async (tx) => {
    const pending = await tx.linkCode.findUnique({ where: { code: normalized } });
    if (!pending) throw new BusinessError("Kode tidak ditemukan. Cek lagi, atau buat baru dengan /link.");
    if (pending.expiresAt.getTime() < Date.now()) {
      // Bersihkan kode mati sekalian.
      await tx.linkCode.delete({ where: { id: pending.id } });
      throw new BusinessError("Kode sudah kedaluwarsa. Buat baru dengan /link di Discord.");
    }

    // UUID Minecraft ini sudah tertaut ke Discord lain?
    const mcTaken = await tx.playerLink.findUnique({ where: { minecraftUuid } });
    if (mcTaken) {
      throw new BusinessError("Akun Minecraft ini sudah tertaut ke Discord lain.");
    }
    // Discord ini sudah tertaut? (balapan: dibuat setelah kode dibuat)
    const discordTaken = await tx.playerLink.findUnique({
      where: { discordId: pending.discordId },
    });
    if (discordTaken) {
      await tx.linkCode.delete({ where: { id: pending.id } });
      throw new BusinessError("Akun Discord ini sudah tertaut.");
    }

    const link = await tx.playerLink.create({
      data: {
        discordId: pending.discordId,
        minecraftUuid,
        minecraftName,
      },
    });

    // Kode sekali pakai — hapus setelah berhasil.
    await tx.linkCode.delete({ where: { id: pending.id } });

    return { link };
  });
}

/** Ambil link berdasarkan Discord ID (atau null). */
export function getLinkByDiscord(discordId) {
  return db.playerLink.findUnique({ where: { discordId } });
}

/** Ambil link berdasarkan UUID Minecraft (atau null). */
export function getLinkByMc(minecraftUuid) {
  return db.playerLink.findUnique({ where: { minecraftUuid } });
}

/**
 * Putuskan tautan akun Discord. Juga bersihkan kode linking yang menggantung.
 * @param {string} discordId
 * @returns {Promise<{link: object}>}  link yang baru saja diputus.
 * @throws {BusinessError} jika belum tertaut.
 */
export async function unlink(discordId) {
  const existing = await db.playerLink.findUnique({ where: { discordId } });
  if (!existing) throw new BusinessError("Akun Discord-mu belum tertaut ke Minecraft.");

  await db.playerLink.delete({ where: { discordId } });
  // Hapus kode pending kalau masih ada (abaikan kalau tidak ada).
  await db.linkCode.deleteMany({ where: { discordId } });

  return { link: existing };
}
