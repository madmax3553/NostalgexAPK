#!/usr/bin/env node
/**
 * Writes the rule-filter fixture the Android unit tests check against.
 *
 * Synthetic items only (no library data). The channel rules and membership manifest are the
 * shared files at the repo root, so the same inputs reach the web filter and the Kotlin filter.
 * The output is android/app/src/test/resources/filter-golden.json, written by the web filter
 * in scripts/nostalgex-channel-filter.cjs. Regenerate it after any change to channels.json or
 * the filter rules: `node scripts/nostalgex-filter-fixture.cjs`.
 *
 * The web filter is a runnable reference, not the tvOS spec. Where the two disagree, read the
 * Swift source (Nostalgex/Nostalgex/App/AppState+Channels.swift) before changing either side.
 */
const fs = require("node:fs");
const path = require("node:path");
const CF = require("./nostalgex-channel-filter.cjs");

const root = path.resolve(__dirname, "..");
const channelsDoc = JSON.parse(fs.readFileSync(path.join(root, "channels.json"), "utf8"));
const memberships = JSON.parse(fs.readFileSync(path.join(root, "channels-memberships.json"), "utf8"));
const NOW_SEC = 1791633600; // 2026-10-10 12:00 UTC, same clock the Kotlin tests use

// Deterministic generator so the fixture is reproducible.
let seed = 12345;
const rand = () => {
  seed = (seed * 1664525 + 1013904223) >>> 0;
  return seed / 4294967296;
};
const pick = (arr) => arr[Math.floor(rand() * arr.length)];

const titles = [
  "See Me", "Stickbird", "Bluey (2018)", "Elf (2003)", "War Games", "Warmth", "Blue Heat",
  "Night of the Living Dead", "The Holiday", "Mockingjay Part 1", "Mockingjay Part 2", "Heat",
  "Cobra", "Waterworld", "Dirty Dancing", "Toy Story", "The Ring", "Scream", "Friends",
  "Seinfeld", "The Office", "Cars", "Akira", "Rudy", "Space Jam", "Alien", "Rocky", "Dune",
  "Stand-Up Special", "Lost Shows", "The Holiday Armadillo", "Super Hero Story", "Cult Film",
];
const genresAll = [
  "Drama", "Comedy", "Horror", "Animation", "Family", "Reality", "Documentary", "War",
  "Western", "Talk Show", "Music", "Action", "Sci-Fi", "Romance", "Crime", "Thriller", "Anime",
  "Game Show", "History", "Sport",
];
const studios = ["Disney", "Netflix", "HBO", "Warner Bros.", "A24", "Pixar", "Universal", null];
const ratings = ["G", "PG", "PG-13", "R", "TV-14", "TV-MA", "TV-Y", null];
const tmdbPool = Object.keys(memberships.items).map((k) => k.split(":")[1]);

const items = [];
for (let i = 0; i < 400; i++) {
  const type = rand() < 0.4 ? "episode" : "movie";
  const ng = 1 + Math.floor(rand() * 3);
  const genres = [];
  while (genres.length < ng) {
    const g = pick(genresAll);
    if (!genres.includes(g)) genres.push(g);
  }
  const srcRoll = rand();
  const librarySource = srcRoll < 0.1 ? "musicVideo" : type === "episode" ? "tv" : "movie";
  items.push({
    ratingKey: `k${i}`,
    title: pick(titles),
    episodeTitle: type === "episode" && rand() < 0.5 ? pick(titles) : null,
    type,
    genres,
    year: 1960 + Math.floor(rand() * 66),
    originallyAvailableAt: rand() < 0.7 ? `${1960 + Math.floor(rand() * 66)}-0${1 + Math.floor(rand() * 9)}-1${Math.floor(rand() * 9)}` : null,
    duration: 10 + Math.floor(rand() * 170),
    rating: Math.round(rand() * 100) / 10,
    userRating: rand() < 0.3 ? Math.round(rand() * 100) / 10 : 0,
    viewCount: Math.floor(rand() * 5),
    addedAt: NOW_SEC - Math.floor(rand() * 400 * 86400),
    studio: pick(studios),
    contentRating: pick(ratings),
    tmdbID: rand() < 0.5 ? pick(tmdbPool) : null,
    librarySource,
  });
}

// Exclusive rules in the shape both filters read: channelIDs as a list.
const exclusiveRules = (channelsDoc.exclusiveRules || []).map((r) => ({
  channelIDs: r.channelIDs ?? (r.channelID != null ? [r.channelID] : []),
  genres: r.genres ?? [],
  titleContains: r.titleContains ?? [],
  editorialTitles: r.editorialTitles ?? [],
  manifestExclusive: r.manifestExclusive ?? false,
}));

const channels = {};
for (const ch of channelsDoc.channels) {
  const pass = CF.filterPool(items, ch, { manifest: memberships, nowSec: NOW_SEC, exclusiveRules });
  channels[String(ch.id)] = pass.items.map((r) => r.ratingKey);
}

const out = {
  generatedBy: "scripts/nostalgex-filter-fixture.cjs",
  nowSec: NOW_SEC,
  items,
  channels,
};
const target = path.join(root, "android/app/src/test/resources/filter-golden.json");
fs.writeFileSync(target, JSON.stringify(out));
const kept = Object.values(channels).reduce((n, a) => n + a.length, 0);
console.log(`wrote ${path.relative(root, target)}: ${items.length} items, ${Object.keys(channels).length} channels, ${kept} kept`);
