// Logika bisnis untuk MailboxItem (kotak surat item menunggu diklaim).
// Handler TIDAK mengakses Prisma langsung — lewat service ini (lihat CLAUDE.md).
import { db } from "../lib/db.js";

/**
 * Titipkan item ke mailbox seorang penerima. Item TIDAK berpindah di sini —
 * stack asli tetap di escrow mod (ditunjuk `escrowRef`); baris ini hanya
 * "label" bahwa penerima berhak mengambilnya nanti via /claim in-game.
 *
 * @param {object} input
 * @param {string} input.ownerId    Discord user ID penerima.
 * @param {string} input.escrowRef  slot escrow di mod yang menyimpan stack.
 * @param {string} input.itemKey    id kanonik MC.
 * @param {string} input.itemLabel  teks tampilan.
 * @param {number} input.quantity
 * @param {"SOLD_PAYMENT"|"CANCELLED_RETURN"|"OVERFLOW"} input.reason
 * @returns {Promise<object>} record MailboxItem.
 */
export function depositToMailbox({ ownerId, escrowRef, itemKey, itemLabel, quantity, reason }) {
  return db.mailboxItem.create({
    data: {
      ownerId,
      escrowRef,
      itemKey,
      itemLabel,
      quantity,
      reason,
    },
  });
}

/** Daftar item mailbox yang belum diklaim milik seorang penerima. */
export function getUnclaimedMailbox(ownerId) {
  return db.mailboxItem.findMany({
    where: { ownerId, claimedAt: null },
    orderBy: { createdAt: "desc" },
  });
}

/**
 * Tandai mailbox item sebagai diklaim. Hanya pemilik yang boleh klaim.
 * Mengembalikan `escrowRef` agar mod bisa menarik stack fisik dari ledger.
 *
 * Idempoten: bila item sudah diklaim sebelumnya, lempar BusinessError agar
 * mod tidak mencoba withdraw ulang (slot sudah kosong).
 *
 * @param {object} input
 * @param {number} input.mailboxId  PK MailboxItem.
 * @param {string} input.ownerId   Discord user ID penuntut klaim.
 * @returns {Promise<{mailboxItem: object, escrowRef: string}>}
 * @throws {import("./transactionService.js").BusinessError}
 */
export async function claimMailboxItem({ mailboxId, ownerId }) {
  const { BusinessError } = await import("./transactionService.js");

  const item = await db.mailboxItem.findUnique({ where: { id: mailboxId } });

  if (!item) throw new BusinessError("Item mailbox tidak ditemukan.");
  if (item.ownerId !== ownerId) {
    throw new BusinessError("Item ini bukan milikmu.");
  }
  if (item.claimedAt !== null) {
    throw new BusinessError("Item ini sudah diklaim sebelumnya.");
  }

  const updated = await db.mailboxItem.update({
    where: { id: mailboxId },
    data: { claimedAt: new Date() },
  });

  return { mailboxItem: updated, escrowRef: item.escrowRef };
}
