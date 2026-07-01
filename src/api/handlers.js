// Handler tiap endpoint REST. Memanggil SERVICE yang sama dengan handler Discord
// (jangan akses Prisma langsung di sini — lihat CLAUDE.md).
//
// Konvensi: tiap handler menerima { body } dan mengembalikan { status, data }.
// `body` sudah di-parse jadi objek (atau {} bila kosong). Error bisnis dilempar
// sebagai BusinessError dan ditangkap oleh server jadi HTTP 400.
import { browseListings } from "../services/listingService.js";
import { redeemLinkCode } from "../services/linkService.js";

/** GET /health — cek server hidup. Tidak butuh logika apa pun. */
export function health() {
  return { status: 200, data: { ok: true, service: "smpmarket-api" } };
}

/**
 * GET /listings — daftar listing ACTIVE untuk ditampilkan in-game.
 * Query opsional: ?type=SELL|BUY, ?page=1.
 * Mengembalikan bentuk ringkas yang gampang dipakai mod.
 */
export async function listings({ query }) {
  const type = query.type === "SELL" || query.type === "BUY" ? query.type : undefined;
  const page = Number(query.page) || 1;

  const result = await browseListings({ type, page });

  return {
    status: 200,
    data: {
      items: result.items.map((l) => ({
        id: l.id,
        type: l.type,
        itemName: l.itemName,
        quantity: l.quantity,
        price: l.price,
        description: l.description ?? null,
        creatorId: l.creatorId,
      })),
      page: result.page,
      totalPages: result.totalPages,
      total: result.total,
    },
  };
}

/**
 * POST /link/redeem — tukar kode linking dari in-game.
 * Body: { code, minecraftUuid, minecraftName }.
 * Dipanggil mod saat pemain ketik /link <kode> di server.
 */
export async function linkRedeem({ body }) {
  const { code, minecraftUuid, minecraftName } = body;

  if (!minecraftUuid || !minecraftName) {
    return {
      status: 400,
      data: { error: "minecraftUuid dan minecraftName wajib diisi." },
    };
  }

  // redeemLinkCode melempar BusinessError bila kode invalid/kedaluwarsa —
  // ditangkap server jadi HTTP 400 dengan pesannya.
  const { link } = await redeemLinkCode({ code, minecraftUuid, minecraftName });

  return {
    status: 200,
    data: {
      ok: true,
      discordId: link.discordId,
      minecraftName: link.minecraftName,
    },
  };
}
