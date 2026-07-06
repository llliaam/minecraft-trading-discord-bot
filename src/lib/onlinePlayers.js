// Registry in-memory pemain Minecraft yang sedang online.
// Diisi mod via POST /players/online saat join, dihapus via DELETE saat quit.
// Tidak perlu persist ke disk — restart server MC ulang registrasi otomatis.
const online = new Set();

export function markOnline(uuid) { online.add(uuid); }
export function markOffline(uuid) { online.delete(uuid); }
export function isOnline(uuid) { return online.has(uuid); }
