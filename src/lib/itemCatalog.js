// Katalog item Minecraft 1.21.1 (subset umum untuk SMP).
//
// Tujuan: memberi `itemKey` KANONIK (mis. "minecraft:diamond") saat pemain
// membuat listing/offer dari Discord. Key kanonik ini WAJIB agar Fase E (escrow)
// bisa mencocokkan pembayaran fisik secara otomatis — teks bebas tak bisa.
//
// In-game, sumber kebenaran item adalah registry Minecraft asli (mod). Katalog
// ini hanya untuk sisi Discord: cukup mencakup item yang lazim diperjualbelikan.
// Menambah item = tambah satu baris di sini.

/**
 * Peta itemKey -> label tampilan. Key TANPA namespace ditulis ringkas;
 * kita tambahkan "minecraft:" secara otomatis di bawah.
 */
const RAW = {
  // ----- Mata uang / barang berharga (sering jadi "harga") -----
  diamond: "Diamond",
  diamond_block: "Diamond Block",
  emerald: "Emerald",
  emerald_block: "Emerald Block",
  netherite_ingot: "Netherite Ingot",
  netherite_block: "Netherite Block",
  gold_ingot: "Gold Ingot",
  gold_block: "Gold Block",
  iron_ingot: "Iron Ingot",
  iron_block: "Iron Block",
  copper_ingot: "Copper Ingot",
  copper_block: "Copper Block",
  netherite_scrap: "Netherite Scrap",
  ancient_debris: "Ancient Debris",
  lapis_lazuli: "Lapis Lazuli",
  redstone: "Redstone Dust",
  redstone_block: "Redstone Block",
  coal: "Coal",
  coal_block: "Coal Block",
  quartz: "Nether Quartz",

  // ----- Ore & raw -----
  raw_iron: "Raw Iron",
  raw_gold: "Raw Gold",
  raw_copper: "Raw Copper",

  // ----- Kayu (log umum) -----
  oak_log: "Oak Log",
  spruce_log: "Spruce Log",
  birch_log: "Birch Log",
  jungle_log: "Jungle Log",
  acacia_log: "Acacia Log",
  dark_oak_log: "Dark Oak Log",
  mangrove_log: "Mangrove Log",
  cherry_log: "Cherry Log",

  // ----- Blok bangunan umum -----
  stone: "Stone",
  cobblestone: "Cobblestone",
  deepslate: "Deepslate",
  dirt: "Dirt",
  sand: "Sand",
  gravel: "Gravel",
  glass: "Glass",
  obsidian: "Obsidian",
  netherrack: "Netherrack",
  end_stone: "End Stone",
  bricks: "Bricks",
  bookshelf: "Bookshelf",

  // ----- Makanan -----
  bread: "Bread",
  cooked_beef: "Steak",
  cooked_porkchop: "Cooked Porkchop",
  cooked_chicken: "Cooked Chicken",
  golden_apple: "Golden Apple",
  enchanted_golden_apple: "Enchanted Golden Apple",
  golden_carrot: "Golden Carrot",
  wheat: "Wheat",
  carrot: "Carrot",
  potato: "Potato",
  sugar_cane: "Sugar Cane",
  melon_slice: "Melon Slice",
  sweet_berries: "Sweet Berries",

  // ----- Alat & senjata (biasanya dijual satuan, sering ber-enchant) -----
  netherite_pickaxe: "Netherite Pickaxe",
  netherite_axe: "Netherite Axe",
  netherite_sword: "Netherite Sword",
  netherite_shovel: "Netherite Shovel",
  netherite_hoe: "Netherite Hoe",
  diamond_pickaxe: "Diamond Pickaxe",
  diamond_axe: "Diamond Axe",
  diamond_sword: "Diamond Sword",
  diamond_shovel: "Diamond Shovel",
  diamond_hoe: "Diamond Hoe",
  bow: "Bow",
  crossbow: "Crossbow",
  trident: "Trident",
  shield: "Shield",
  fishing_rod: "Fishing Rod",
  shears: "Shears",
  flint_and_steel: "Flint and Steel",

  // ----- Armor -----
  netherite_helmet: "Netherite Helmet",
  netherite_chestplate: "Netherite Chestplate",
  netherite_leggings: "Netherite Leggings",
  netherite_boots: "Netherite Boots",
  diamond_helmet: "Diamond Helmet",
  diamond_chestplate: "Diamond Chestplate",
  diamond_leggings: "Diamond Leggings",
  diamond_boots: "Diamond Boots",
  elytra: "Elytra",
  turtle_helmet: "Turtle Helmet",

  // ----- Utility / rare -----
  ender_pearl: "Ender Pearl",
  ender_eye: "Eye of Ender",
  blaze_rod: "Blaze Rod",
  ghast_tear: "Ghast Tear",
  nether_star: "Nether Star",
  experience_bottle: "Bottle o' Enchanting",
  totem_of_undying: "Totem of Undying",
  enchanted_book: "Enchanted Book",
  name_tag: "Name Tag",
  saddle: "Saddle",
  lead: "Lead",
  book: "Book",
  paper: "Paper",
  string: "String",
  gunpowder: "Gunpowder",
  slime_ball: "Slime Ball",
  honey_bottle: "Honey Bottle",
  amethyst_shard: "Amethyst Shard",
  echo_shard: "Echo Shard",
  heart_of_the_sea: "Heart of the Sea",
  nautilus_shell: "Nautilus Shell",
  prismarine_shard: "Prismarine Shard",
  prismarine_crystals: "Prismarine Crystals",
  bone: "Bone",
  bone_meal: "Bone Meal",
  gold_nugget: "Gold Nugget",
  iron_nugget: "Iron Nugget",
  clay_ball: "Clay Ball",
  brick: "Brick",
  leather: "Leather",
  feather: "Feather",
  glowstone_dust: "Glowstone Dust",
  glow_ink_sac: "Glow Ink Sac",
  ink_sac: "Ink Sac",
  honeycomb: "Honeycomb",
  scute: "Turtle Scute",

  // ----- Spawn-adjacent / dekorasi populer -----
  glowstone: "Glowstone",
  sea_lantern: "Sea Lantern",
  shroomlight: "Shroomlight",
  tnt: "TNT",
  torch: "Torch",
};

// Bangun daftar final: { key: "minecraft:xxx", label: "Xxx", search: "xxx label lower" }
const CATALOG = Object.entries(RAW).map(([short, label]) => {
  const key = `minecraft:${short}`;
  return {
    key,
    label,
    // string pencarian gabungan (nama pendek + label), lowercase.
    search: `${short} ${label}`.toLowerCase(),
  };
});

// Set untuk validasi O(1).
const VALID_KEYS = new Set(CATALOG.map((e) => e.key));

/**
 * Cari item untuk autocomplete Discord (maksimal 25 — batas Discord).
 * Cocokkan pada nama pendek ATAU label, case-insensitive, substring.
 * @param {string} query  ketikan pemain (boleh kosong).
 * @returns {{name: string, value: string}[]}  siap dipakai respond().
 */
export function findItems(query) {
  const q = (query ?? "").trim().toLowerCase();
  const matches = q
    ? CATALOG.filter((e) => e.search.includes(q))
    : CATALOG;

  return matches.slice(0, 25).map((e) => ({
    // Tampilkan label + key kanonik biar pemain yakin item yang benar.
    name: `${e.label} (${e.key})`.slice(0, 100),
    value: e.key,
  }));
}

/** True bila key ada di katalog (kanonik & dikenal). */
export function isValidKey(key) {
  return VALID_KEYS.has(key);
}

/**
 * Normalisasi input bebas dari pemain jadi entri katalog. Dipakai di tempat
 * yang TAK bisa autocomplete (mis. modal Discord). Menerima:
 *   "minecraft:diamond" | "diamond" | "Diamond" | "diamond block"
 * dan mencocokkan ke katalog (via key kanonik ATAU label). Spasi → underscore.
 * @param {string} input
 * @returns {{key: string, label: string}|null}  null bila tak dikenal.
 */
export function normalizeToKey(input) {
  const raw = String(input ?? "").trim().toLowerCase();
  if (!raw) return null;

  // Bentuk kandidat key kanonik: pastikan ada namespace, spasi → underscore.
  const withNs = raw.includes(":") ? raw : `minecraft:${raw}`;
  const candidate = withNs.replace(/\s+/g, "_");

  const byKey = CATALOG.find((e) => e.key === candidate);
  if (byKey) return { key: byKey.key, label: byKey.label };

  // Coba cocokkan lewat label persis (mis. "diamond block").
  const byLabel = CATALOG.find((e) => e.label.toLowerCase() === raw);
  if (byLabel) return { key: byLabel.key, label: byLabel.label };

  return null;
}

/**
 * Label tampilan untuk sebuah key. Bila tak ada di katalog (mis. item ber-NBT
 * dari in-game), rapikan dari key mentah: "minecraft:diamond_sword" → "Diamond Sword".
 * @param {string} key
 * @returns {string}
 */
export function prettify(key) {
  const found = CATALOG.find((e) => e.key === key);
  if (found) return found.label;

  // Fallback: buang namespace, ubah underscore → spasi, Title Case.
  const bare = String(key ?? "").replace(/^.*:/, "");
  if (!bare) return String(key ?? "");
  return bare
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

/**
 * Format harga terstruktur jadi teks tampilan: (64, "minecraft:diamond") → "64× Diamond".
 * @param {number} quantity
 * @param {string} itemKey
 * @returns {string}
 */
export function formatPrice(quantity, itemKey) {
  return `${quantity}× ${prettify(itemKey)}`;
}
