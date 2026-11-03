#!/usr/bin/env node
/**
 * One-shot lineup consolidation (v38): 131 channels in 14 bundles down to 19 year-round
 * channels plus the four seasonal members. Reads the v37 channels.json and
 * channels-memberships.json in place and rewrites both.
 * Run once: node scripts/migrate-lineup-v38.mjs
 */
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const configPath = path.join(root, "channels.json");
const manifestPath = path.join(root, "channels-memberships.json");

const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
if (config.version !== 37) throw new Error(`Expected channels.json v37, found v${config.version}`);
const old = Object.fromEntries(config.channels.map((c) => [c.id, c]));
const clone = (v) => JSON.parse(JSON.stringify(v));
const rulesOf = (id) => clone(old[id].rules);
const union = (...lists) => [...new Set(lists.flat())];
const kw = (...ids) => union(...ids.map((id) => old[id].rules.keywords || []));

const ch = (id, number, name, colorHex, category, minItems, rules, extra = {}) => ({
  id, number, name, colorHex, category, minItems, rules, ...extra,
});

const NOT_KIDS = ["Family", "Kids", "Children"];
const NOT_CARTOON = ["Animation", "Animated"];

const channels = [
  ch(1, 1, "REWATCHABLES", "#FFE500", "nostalgex", 5, { rewatched: true }),
  ch(2, 2, "VHS VAULT", old[7].colorHex, "nostalgex", 5, rulesOf(7), { timeRestrictions: clone(old[7].timeRestrictions) }),
  ch(3, 3, "DVD SHELF", old[405].colorHex, "nostalgex", 5, rulesOf(405)),
  ch(4, 4, "LATE NIGHT", "#6C1C8A", "nostalgex", 3, {
    type: "movie",
    genres: { include: ["Horror", "Thriller"], exclude: [...NOT_KIDS, ...NOT_CARTOON, "Comedy"] },
    keywords: ["cult film", "slasher", "midnight movie", "erotic thriller", "exploitation", "grindhouse",
      "giallo", "body horror", "splatter", "psychological thriller"],
    keywordsRequireAnyGenre: ["Horror", "Thriller"],
    keywordGatedGenres: ["Thriller"],
    yearRange: { min: 1975, max: 2009 },
    contentRatings: ["R", "NC-17", "PG-13"],
  }, { timeRestrictions: clone(old[7].timeRestrictions) }),
  ch(5, 5, "SATURDAY MATINEE", "#B9770E", "nostalgex", 5, {
    type: "movie",
    genres: {
      include: ["Western", "War", "War & Politics", "Adventure", "Drama", "Action", "Crime", "Mystery",
        "Thriller", "Science Fiction", "Fantasy", "Musical"],
      exclude: [...NOT_KIDS, ...NOT_CARTOON],
    },
    yearRange: { min: 1930, max: 1979 },
  }),

  ch(6, 6, "LOL", old[57].colorHex, "movies", 5, {
    type: "movie",
    genres: { include: ["Comedy"], exclude: ["Horror", "War", "History", "Documentary", ...NOT_KIDS] },
    keywordsExclude: old[57].rules.keywordsExclude,
    ratingMin: 6,
  }),
  ch(7, 7, "ACTION", "#E74C3C", "movies", 5, {
    type: "movie",
    genres: {
      include: ["Action", "Adventure", "War", "War & Politics", "Western"],
      exclude: [...NOT_KIDS, ...NOT_CARTOON, "Fantasy", "Science Fiction", "Comedy"],
    },
    keywords: union(old[13].rules.keywords, old[72].rules.keywords),
    keywordsRequireAnyGenre: ["Action", "Thriller", "Adventure"],
    yearRange: { min: 1980 },
    durationRange: { min: 80 },
  }),
  ch(8, 8, "SCI-FI & FANTASY", old[73].colorHex, "movies", 5, {
    type: "movie",
    genres: { include: ["Science Fiction", "Fantasy"], exclude: [...NOT_CARTOON, ...NOT_KIDS] },
    keywords: kw(73, 74),
    keywordsRequireAnyGenre: ["Science Fiction", "Fantasy"],
    yearRange: { min: 1980 },
  }),
  ch(9, 9, "CRIME & THRILLERS", old[41].colorHex, "movies", 5, {
    type: "movie",
    genres: { include: ["Crime", "Thriller", "Mystery"], exclude: ["Horror", "Documentary", ...NOT_KIDS, ...NOT_CARTOON] },
    keywords: kw(41, 42, 43),
    keywordsRequireAnyGenre: ["Crime", "Thriller", "Mystery"],
    yearRange: { min: 1980 },
  }),
  ch(10, 10, "DATE NIGHT", old[52].colorHex, "movies", 5, {
    type: "movie",
    genres: { include: ["Romance"], exclude: ["Horror", "Thriller", "Action", ...NOT_CARTOON, ...NOT_KIDS] },
    keywords: old[64].rules.keywords,
    keywordsRequireAnyGenre: ["Romance"],
  }),
  ch(11, 11, "ALL TIME GREATS", old[67].colorHex, "movies", 5, {
    type: "movie",
    ratingMin: 8,
    durationRange: { min: 60 },
    editorialOverrides: old[69].rules.titleContains,
  }),
  ch(12, 12, "FRESH", old[403].colorHex, "movies", 1, { ...rulesOf(403), addedWithinDays: 30 },
    { isPremiereChannel: true }),

  ch(13, 13, "SITCOMS", old[50].colorHex, "tv", 10, rulesOf(50)),
  ch(14, 14, "DRAMA TV", old[200].colorHex, "tv", 10, rulesOf(200)),
  ch(15, 15, "CRIME TV", old[44].colorHex, "tv", 3, {
    type: "episode",
    genres: { include: ["Crime", "Documentary", "Docuseries"], exclude: [...NOT_CARTOON, ...NOT_KIDS] },
    keywords: union(old[44].rules.keywords, old[40].rules.keywords),
    keywordsRequireAnyGenre: ["Crime", "Documentary", "Docuseries"],
    keywordGatedGenres: ["Documentary", "Docuseries"],
  }),
  ch(16, 16, "DOCS", old[56].colorHex, "tv", 5, {
    genres: { include: ["Documentary", "Docuseries", "History", "Biography"], exclude: [...NOT_CARTOON, ...NOT_KIDS] },
    keywords: old[56].rules.keywords,
    keywordsRequireAnyGenre: ["Documentary"],
    keywordsExclude: ["true crime", "serial killer", "murder"],
  }),
  ch(17, 17, "REALITY TV", old[54].colorHex, "tv", 5, {
    type: "episode",
    genres: { include: ["Reality", "Reality-TV", "Docuseries", "Game Show", "Game-Show", "Competition"] },
  }),

  ch(18, 18, "TOONS", old[4].colorHex, "kids", 5, {
    genres: { include: ["Animation", "Animated", "Cartoon", "Anime", "Kids", "Children"] },
  }),
  ch(19, 19, "FAMILY HOUR", old[20].colorHex, "kids", 5, rulesOf(20)),

  { ...clone(old[130]), number: 20 },
  { ...clone(old[413]), number: 21, name: "CHRISTMAS TV",
    rules: (({ contentRatings, ...rest }) => rest)(clone(old[413].rules)) },
  { ...clone(old[141]), number: 22, category: "seasonal", minItems: 3 },
  { ...clone(old[131]), number: 23 },
];

const bundles = [
  { id: "nostalgex", name: "RETRO CABLE",
    description: "Rewatchables, VHS, DVD and late night, the way cable used to run",
    channelIDs: [1, 2, 3, 4, 5] },
  { id: "movies", name: "MOVIES",
    description: "Comedy, action, sci-fi, crime, romance and the greats",
    channelIDs: [6, 7, 8, 9, 10, 11, 12] },
  { id: "tv", name: "TV",
    description: "Sitcoms, drama, crime, docs and reality",
    channelIDs: [13, 14, 15, 16, 17] },
  { id: "kids", name: "KIDS & FAMILY",
    description: "Cartoons and family movies",
    channelIDs: [18, 19] },
  { id: "tis-the-season", name: "HOLIDAZE",
    description: "Christmas movies and specials, every December",
    channelIDs: [130, 413], activeMonths: [12] },
  { id: "seasonal", name: "FRIGHT NIGHT",
    description: "Horror and Halloween classics, every October",
    channelIDs: [141, 131], activeMonths: [10] },
];

const exclusiveRules = clone(config.exclusiveRules);
exclusiveRules[0].channelIDs = [130, 413];
exclusiveRules[1].channelIDs = [18];
exclusiveRules[2] = { ...exclusiveRules[2], channelID: 141 };

// Old channel id -> new channel ids. Anything not listed loses its claims.
const MAP = {
  1: [1], 2: [1],
  7: [2], 10: [2],
  405: [3],
  3: [6], 406: [6], 402: [6], 57: [6],
  14: [7], 12: [7], 13: [7], 72: [7], 66: [7], 219: [7], 71: [7], 75: [5, 7], 76: [5, 7],
  65: [8], 70: [8], 73: [8], 74: [8],
  41: [9], 42: [9], 43: [9],
  64: [10], 52: [10], 9: [10],
  67: [11], 68: [11], 69: [11],
  62: [12], 403: [12],
  50: [13], 5: [13], 6: [13], 407: [13], 53: [13],
  200: [14],
  44: [15], 40: [15],
  56: [16], 404: [16], 201: [16],
  54: [17], 55: [17],
  4: [18], 11: [18], 215: [18], 216: [18], 25: [18], 22: [18], 26: [18], 27: [18], 28: [18],
  218: [18], 400: [18], 63: [18], 60: [18], 23: [18],
  20: [19], 8: [19],
  130: [130], 409: [130], 410: [130], 411: [130], 412: [130],
  413: [413], 414: [413],
  141: [141], 132: [141], 408: [141],
  131: [131],
};

const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
const items = {};
let kept = 0;
let dropped = 0;
for (const [key, entry] of Object.entries(manifest.items)) {
  const next = union(entry.channels.flatMap((id) => MAP[id] || []));
  // The stand-up lock stays: those specials keep being hidden from every other channel.
  const exclusive = entry.exclusive === 58 ? 58 : entry.exclusive != null ? (MAP[entry.exclusive] || [])[0] : undefined;
  if (next.length === 0 && exclusive == null) { dropped++; continue; }
  const out = { ...entry, channels: next };
  if (exclusive != null) out.exclusive = exclusive; else delete out.exclusive;
  items[key] = out;
  kept++;
}

fs.writeFileSync(configPath, JSON.stringify({ ...config, version: 38, exclusiveRules, bundles, channels }, null, 2));
fs.writeFileSync(manifestPath, JSON.stringify({ ...manifest, items }, null, 2));
console.log(`channels.json v38: ${channels.length} channels, ${bundles.length} bundles`);
console.log(`channels-memberships.json: kept ${kept} items, dropped ${dropped}`);
