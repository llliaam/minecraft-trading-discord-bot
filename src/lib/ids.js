// Konvensi customId tombol & modal.
// Format: "action:entityId" — handler memparse untuk tahu entitas mana yang dimaksud.
// customId Discord dibatasi 100 karakter; format pendek ini aman.

export const Action = {
  MAKE_OFFER: "offer", // tombol Make Offer di embed listing → arahkan ke /offer
  // OFFER_MODAL dipensiunkan: pembuatan offer kini via slash /offer (modal Discord
  // tak mendukung autocomplete item). Dibiarkan sbg catatan sejarah, tak dipakai.
  OFFER_ACCEPT: "oaccept", // tombol Accept offer → offerId
  OFFER_REJECT: "oreject", // tombol Reject offer → offerId
  TX_COMPLETE: "txdone", // tombol Mark Completed → transactionId
  BROWSE_PAGE: "bpage", // tombol Prev/Next /browse → "TYPE:page" (lihat encodeBrowse)
};

/** Bangun customId dari action + id. Contoh: build("buynow", 12) → "buynow:12" */
export function buildId(action, entityId) {
  return `${action}:${entityId}`;
}

/**
 * Parse customId jadi { action, entityId }.
 * entityId dikembalikan sebagai Number (semua entity kita pakai PK integer).
 * Mengembalikan null jika format tidak dikenal.
 */
export function parseId(customId) {
  const idx = customId.indexOf(":");
  if (idx === -1) return null;
  const action = customId.slice(0, idx);
  const entityId = Number(customId.slice(idx + 1));
  if (Number.isNaN(entityId)) return null;
  return { action, entityId };
}

// ===== Pagination /browse =====
// customId menyimpan state filter + halaman: "bpage:<TYPE>:<page>".
// TYPE = "ALL" | "SELL" | "BUY".

/** Bangun customId tombol pagination browse. */
export function encodeBrowse(type, page) {
  return `${Action.BROWSE_PAGE}:${type ?? "ALL"}:${page}`;
}

/**
 * Parse customId pagination browse → { type, page }.
 * type = undefined jika "ALL" (artinya tanpa filter). null jika format salah.
 */
export function decodeBrowse(customId) {
  const parts = customId.split(":");
  if (parts.length !== 3 || parts[0] !== Action.BROWSE_PAGE) return null;
  const [, rawType, rawPage] = parts;
  const page = Number(rawPage);
  if (Number.isNaN(page)) return null;
  const type = rawType === "ALL" ? undefined : rawType;
  return { type, page };
}
