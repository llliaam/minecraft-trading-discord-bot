// Sekali pakai: kosongkan tabel marketplace (data tes) TANPA menyentuh
// PlayerLink/LinkCode, agar migrasi schema bisa jalan tanpa --force-reset.
import { PrismaClient } from "@prisma/client";
const db = new PrismaClient();

const tx = await db.transaction.deleteMany({});
const of = await db.offer.deleteMany({});
const li = await db.listing.deleteMany({});
const links = await db.playerLink.count();

console.log(`Dihapus: ${tx.count} transaction, ${of.count} offer, ${li.count} listing.`);
console.log(`PlayerLink tetap utuh: ${links} baris.`);

await db.$disconnect();
