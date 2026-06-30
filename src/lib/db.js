// Prisma client singleton.
// Satu instance dipakai seluruh aplikasi agar tidak kehabisan koneksi DB.
import { PrismaClient } from "@prisma/client";

export const db = new PrismaClient();
