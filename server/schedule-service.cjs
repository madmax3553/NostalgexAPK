#!/usr/bin/env node
// StaticGroot companion: immutable day manifests and shared channel setup.
// Clients propose schedules; the first valid publication wins atomically.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { PlaybackProfiles } = require('./playback-profiles.cjs');

class StationStore {
  constructor(directory) {
    this.directory = directory;
    fs.mkdirSync(directory, { recursive: true });
  }
  stationPath(station) {
    if (!/^[a-f0-9]{64}$/.test(station)) throw new Error('Invalid station id');
    const dir = path.join(this.directory, station);
    fs.mkdirSync(dir, { recursive: true });
    return dir;
  }
  read(file) { return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : null; }
  writeNew(file, value) {
    // Synchronous operation in this single-process server; rename publishes a complete file.
    if (fs.existsSync(file)) return this.read(file);
    const tmp = `${file}.${crypto.randomUUID()}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(value), { mode: 0o600 });
    fs.renameSync(tmp, file);
    return value;
  }
  setup(station, proposal) {
    const file = path.join(this.stationPath(station), 'setup.json');
    const saved = this.read(file);
    if (saved) return saved;
    if (!proposal || !Array.isArray(proposal.channels) || !proposal.timeZone) throw new Error('Setup required');
    new Intl.DateTimeFormat('en', { timeZone: proposal.timeZone });
    const ids = proposal.channels.map(c => c.id);
    if (ids.length > 1000 || ids.some(id => !Number.isSafeInteger(id) || id < 1) || new Set(ids).size !== ids.length)
      throw new Error('Invalid channels');
    return this.writeNew(file, { version: 1, timeZone: proposal.timeZone, channels: proposal.channels });
  }
  updateSetup(station, proposal) {
    const saved = this.setup(station);
    if (proposal.version !== saved.version) { const error = new Error('Setup changed; reload before saving'); error.status = 409; throw error; }
    if (!Array.isArray(proposal.channels) || proposal.channels.length !== saved.channels.length ||
        proposal.channels.some((c, i) => c.id !== saved.channels[i].id || typeof c.enabled !== 'boolean'))
      throw new Error('Only channel enabled flags may be changed');
    const value = { ...saved, version: saved.version + 1,
      channels: saved.channels.map((c, i) => ({ ...c, enabled: proposal.channels[i].enabled })) };
    const file = path.join(this.stationPath(station), 'setup.json');
    const tmp = `${file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(value), { mode: 0o600 }); fs.renameSync(tmp, file);
    return value;
  }
  day(station, channelId, dayKey, proposal) {
    if (!Number.isSafeInteger(channelId) || channelId < 1 || !/^\d{4}-\d{2}-\d{2}$/.test(dayKey))
      throw new Error('Invalid channel or day');
    const setup = this.setup(station);
    const dir = setup.generation ? path.join(this.stationPath(station), `generation-${setup.generation}`) : this.stationPath(station);
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, `${channelId}_${dayKey}.json`);
    const saved = this.read(file);
    if (saved) return saved;
    if (!proposal) return null;
    if (proposal.generation !== undefined && proposal.generation !== (setup.generation || 0)) {
      const error = new Error('Schedule regenerated; restart to reload'); error.status = 409; throw error;
    }
    if (!setup.channels.some(c => c.id === channelId)) throw new Error('Unknown channel');
    const blocks = proposal.blocks;
    if (!Array.isArray(blocks) || blocks.length > 10000) throw new Error('Invalid blocks');
    let previousEnd = null;
    let lastMovie = null;
    let lastMovieEnd = null;
    for (const b of blocks) {
      if (typeof b.itemId !== 'string' || !Number.isSafeInteger(b.startUnix) || !Number.isSafeInteger(b.endUnix) ||
          b.endUnix <= b.startUnix || !Number.isSafeInteger(b.mediaOffsetSeconds) || b.mediaOffsetSeconds < 0 ||
          typeof b.isBumper !== 'boolean' || typeof b.isMidBreak !== 'boolean' ||
          (previousEnd !== null && b.startUnix !== previousEnd)) throw new Error('Invalid programme timing');
      if (!b.isBumper && b.mediaOffsetSeconds === 0 && b.movieIdentity) {
        if (lastMovie === b.movieIdentity && b.startUnix - lastMovieEnd < 300)
          throw new Error('Movie repeated back-to-back');
        lastMovie = b.movieIdentity;
        lastMovieEnd = b.endUnix;
      } else if (!b.isBumper && b.movieIdentity === lastMovie) {
        lastMovieEnd = b.endUnix;
      }
      previousEnd = b.endUnix;
    }
    const value = { schemaVersion: 1, channelId, dayKey, publishedAt: Date.now(), blocks };
    return this.writeNew(file, value);
  }
  regenerate(station, proposal) {
    const setup = this.setup(station);
    if (proposal.generation !== (setup.generation || 0)) { const e = new Error('Generation conflict'); e.status = 409; throw e; }
    if (!Array.isArray(proposal.days) || proposal.days.length > 3000) throw new Error('Invalid days');
    const generation = (setup.generation || 0) + 1;
    const stationDir = this.stationPath(station);
    const dir = path.join(stationDir, `generation-${generation}`);
    if (fs.existsSync(dir)) fs.rmSync(dir, { recursive: true });
    fs.mkdirSync(dir);
    // Validate all proposals in an isolated store before changing the active pointer.
    const scratch = fs.mkdtempSync(path.join(stationDir, 'validate-'));
    try {
      const validator = new StationStore(scratch);
      validator.setup(station, setup);
      for (const day of proposal.days) {
        const checked = validator.day(station, day.channelId, day.dayKey, { blocks: day.blocks });
        fs.writeFileSync(path.join(dir, `${day.channelId}_${day.dayKey}.json`), JSON.stringify(checked), { mode: 0o600 });
      }
      const updated = { ...setup, generation };
      const file = path.join(stationDir, 'setup.json');
      fs.writeFileSync(`${file}.tmp`, JSON.stringify(updated), { mode: 0o600 });
      fs.renameSync(`${file}.tmp`, file);
      return updated;
    } finally { fs.rmSync(scratch, { recursive: true, force: true }); }
  }
}

function createServer({ directory, token }) {
  if (!token || token.length < 24) throw new Error('STATICGROOT_TOKEN must contain at least 24 characters');
  const store = new StationStore(directory);
  const expected = Buffer.from(`Bearer ${token}`);
  return http.createServer(async (req, res) => {
    const reply = (code, body) => {
      res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify(body));
    };
    const got = Buffer.from(req.headers.authorization || '');
    if (got.length !== expected.length || !crypto.timingSafeEqual(got, expected)) return reply(401, { error: 'Unauthorized' });
    try {
      const parts = new URL(req.url, 'http://localhost').pathname.split('/').filter(Boolean);
      if (parts[0] !== 'v1' || parts[1] !== 'stations') return reply(404, { error: 'Not found' });
      let body;
      if (req.method === 'POST' || req.method === 'PUT') {
        let bytes = 0; const chunks = [];
        for await (const chunk of req) {
          bytes += chunk.length;
          if (bytes > 8 * 1024 * 1024) { const e = new Error('Request too large'); e.status = 413; throw e; }
          chunks.push(chunk);
        }
        body = JSON.parse(Buffer.concat(chunks).toString());
      }
      const station = parts[2];
      if (parts[3] === 'catalog' && parts.length === 4 && req.method === 'POST') {
        if (!Array.isArray(body.items) || body.items.length > 100000) throw new Error('Invalid catalog');
        const catalog = {};
        for (const item of body.items) {
          if (typeof item.id !== 'string' || typeof item.title !== 'string' || !['movie', 'episode'].includes(item.type) ||
              (item.seriesId != null && typeof item.seriesId !== 'string')) throw new Error('Invalid catalog item');
          catalog[item.id] = { title: item.title, type: item.type, seriesId: item.seriesId || null };
        }
        const file = path.join(store.stationPath(station), 'catalog.json');
        fs.writeFileSync(`${file}.tmp`, JSON.stringify(catalog), { mode: 0o600 }); fs.renameSync(`${file}.tmp`, file);
        return reply(200, { items: body.items.length });
      }
      if (parts[3] === 'playback' && parts.length === 4) {
        const profiles = new PlaybackProfiles(path.join(store.stationPath(station), 'playback'));
        if (req.method === 'POST') return reply(200, profiles.observe(body));
        if (req.method === 'GET') {
          const query = new URL(req.url, 'http://localhost').searchParams;
          return reply(200, profiles.get(query.get('itemId'), query.get('source'), query.get('device')));
        }
      }
      if (parts[3] === 'regenerate' && parts.length === 4 && req.method === 'POST')
        return reply(200, store.regenerate(station, body));
      if (parts[3] === 'selection' && parts.length === 4) {
        const file = path.join(store.stationPath(station), 'selection.json');
        if (req.method === 'GET') return reply(200, store.read(file) || { channelId: null });
        if (req.method === 'POST') {
          if (!store.setup(station).channels.some(c => c.id === body.channelId)) throw new Error('Unknown channel');
          const tmp = `${file}.tmp`;
          fs.writeFileSync(tmp, JSON.stringify({ channelId: body.channelId }), { mode: 0o600 }); fs.renameSync(tmp, file);
          return reply(200, { channelId: body.channelId });
        }
      }
      if (parts[3] === 'setup' && parts.length === 4) {
        if (req.method === 'POST') return reply(200, store.setup(station, body));
        if (req.method === 'GET') return reply(200, store.setup(station));
        if (req.method === 'PUT') return reply(200, store.updateSetup(station, body));
      }
      if (parts[3] === 'days' && parts.length === 6 && ['GET', 'POST'].includes(req.method)) {
        const day = store.day(station, Number(parts[4]), parts[5], body);
        return reply(day ? 200 : 404, day || { error: 'Day not published' });
      }
      reply(405, { error: 'Method not allowed' });
    } catch (error) { reply(error.status || 400, { error: error.message }); }
  });
}

module.exports = { StationStore, createServer };
if (require.main === module) {
  const server = createServer({ directory: process.env.STATICGROOT_DATA || '/data', token: process.env.STATICGROOT_TOKEN });
  server.listen(Number(process.env.PORT || 8787), process.env.HOST || '0.0.0.0', () => console.log('StaticGroot schedules listening'));
}
