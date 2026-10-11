#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');

/** Compare distinct aired items, not continuation segments, ad breaks or repeat frequency. */
function auditChannels(channels, manifests, minimumShared = 3) {
  const items = new Map(channels.map(ch => [ch.id, new Set()]));
  const days = new Set();
  for (const day of manifests) {
    days.add(day.dayKey);
    const pool = items.get(day.channelId);
    if (!pool) continue;
    for (const block of day.blocks || []) {
      if (!block.isBumper) pool.add(block.movieIdentity || `item:${block.itemId}`);
    }
  }
  const pairs = [];
  for (let i = 0; i < channels.length; i++) {
    for (let j = i + 1; j < channels.length; j++) {
      const a = items.get(channels[i].id), b = items.get(channels[j].id);
      if (!a.size || !b.size) continue;
      const shared = [...a].filter(id => b.has(id)).length;
      if (shared < minimumShared) continue;
      const union = a.size + b.size - shared;
      const coverageA = shared / a.size, coverageB = shared / b.size;
      const jaccard = shared / union;
      pairs.push({
        channelA: channels[i].id, channelB: channels[j].id,
        sharedItems: shared, uniqueA: a.size - shared, uniqueB: b.size - shared,
        coverageA: +coverageA.toFixed(3), coverageB: +coverageB.toFixed(3),
        jaccard: +jaccard.toFixed(3),
        // Recommendation only. Distinct editorial rules and programmes still need review.
        candidate: coverageA >= .9 && coverageB >= .9 ? 'near-duplicate' :
          coverageA >= .95 || coverageB >= .95 ? 'mostly-contained' : 'overlap',
      });
    }
  }
  pairs.sort((a, b) => b.jaccard - a.jaccard || b.sharedItems - a.sharedItems || a.channelA - b.channelA);
  return { basis: 'distinct aired items in active published manifests, not full eligible pools',
    days: days.size, populatedChannels: [...items.values()].filter(set => set.size > 0).length,
    comparisons: pairs.length, nearDuplicates: pairs.filter(pair => pair.candidate === 'near-duplicate').length,
    pairs };
}

/** Shared series airtime in a fixed wall-clock window. Different episodes still overlap. */
function auditWindow(channels, manifests, fromUnix, catalog = {}, threshold = .6) {
  const duration = 7200;
  if (!Number.isSafeInteger(fromUnix)) throw new Error('Window start must be Unix seconds');
  const toUnix = fromUnix + duration;
  const airtime = new Map(channels.map(ch => [ch.id, new Map()]));
  let unresolvedSeconds = 0;
  for (const day of manifests) {
    const totals = airtime.get(day.channelId);
    if (!totals) continue;
    for (const block of day.blocks || []) {
      if (block.isBumper) continue;
      const seconds = Math.max(0, Math.min(block.endUnix, toUnix) - Math.max(block.startUnix, fromUnix));
      if (!seconds) continue;
      const metadata = catalog[block.itemId];
      const identity = metadata?.seriesId ? `series:${metadata.seriesId}` :
        metadata?.type === 'episode' && metadata.title ? `series-title:${metadata.title.toLowerCase()}` :
          block.movieIdentity || `item:${block.itemId}`;
      if (!metadata && !block.movieIdentity) unresolvedSeconds += seconds;
      totals.set(identity, (totals.get(identity) || 0) + seconds);
    }
  }
  const pairs = [];
  for (let i = 0; i < channels.length; i++) for (let j = i + 1; j < channels.length; j++) {
    const a = airtime.get(channels[i].id), b = airtime.get(channels[j].id);
    const sharedSeconds = [...a].reduce((total, [key, seconds]) => total + Math.min(seconds, b.get(key) || 0), 0);
    const overlap = Math.min(1, sharedSeconds / duration);
    if (overlap < threshold) continue;
    pairs.push({ channelA: channels[i].id, channelB: channels[j].id,
      nameA: channels[i].name, nameB: channels[j].name,
      sharedMinutes: +(sharedSeconds / 60).toFixed(1), overlap: +overlap.toFixed(3), candidate: 'two-hour-overlap' });
  }
  pairs.sort((a, b) => b.overlap - a.overlap || a.channelA - b.channelA);
  return { basis: 'shared series/movie airtime divided by the full two-hour window; breaks excluded',
    fromUnix, toUnix, threshold, unresolvedSeconds, pairs };
}

function auditDirectory(stationDir, fromUnix) {
  const setup = JSON.parse(fs.readFileSync(path.join(stationDir, 'setup.json'), 'utf8'));
  const active = setup.generation ? path.join(stationDir, `generation-${setup.generation}`) : stationDir;
  const manifests = fs.readdirSync(active).filter(name => /^\d+_\d{4}-\d{2}-\d{2}\.json$/.test(name))
    .map(name => JSON.parse(fs.readFileSync(path.join(active, name), 'utf8')));
  const catalogFile = path.join(stationDir, 'catalog.json');
  const catalog = fs.existsSync(catalogFile) ? JSON.parse(fs.readFileSync(catalogFile, 'utf8')) : {};
  return fromUnix === undefined ? auditChannels(setup.channels, manifests) :
    auditWindow(setup.channels, manifests, fromUnix, catalog);
}

module.exports = { auditChannels, auditWindow, auditDirectory };
if (require.main === module) {
  if (!process.argv[2]) { console.error('Usage: node server/channel-audit.cjs /data/<station-id>'); process.exit(1); }
  console.log(JSON.stringify(auditDirectory(process.argv[2], process.argv[3] ? Number(process.argv[3]) : undefined), null, 2));
}
