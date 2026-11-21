const fs = require('node:fs');

const KINDS = ['player_error', 'stall_start', 'stall_rebuffer', 'no_stream', 'tune_failed'];
const ROUTES = ['lan', 'tailscale', 'other'];
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_LINES = 1000;

/**
 * Failed streams, one JSON line each, so a failure can be diagnosed after the TV's own log
 * has rolled over. Lines older than a week are dropped on every write, and the line cap
 * bounds a TV that fails in a loop.
 */
class FailureLog {
  constructor(file) { this.file = file; }
  read(now = Date.now()) {
    if (!fs.existsSync(this.file)) return [];
    const kept = [];
    for (const line of fs.readFileSync(this.file, 'utf8').split('\n')) {
      if (!line) continue;
      try {
        const entry = JSON.parse(line);
        if (now - entry.receivedAt <= MAX_AGE_MS) kept.push(entry);
      } catch { /* a torn line is dropped */ }
    }
    return kept;
  }
  add(body, now = Date.now()) {
    const text = (value, max) => typeof value === 'string' && value.length > 0 && value.length <= max;
    const optionalText = (value, max) => value == null || text(value, max);
    const count = value => Number.isSafeInteger(value) && value >= 0;
    const optionalCount = value => value == null || count(value);
    const optionalFlag = value => value == null || typeof value === 'boolean';
    if (!body || !KINDS.includes(body.kind) || !ROUTES.includes(body.route) ||
        !text(body.appVersion, 32) || !text(body.device, 64) ||
        !optionalCount(body.channelId) || !optionalText(body.itemId, 64) || !optionalText(body.mediaSourceId, 64) ||
        typeof body.commercial !== 'boolean' || !optionalFlag(body.direct) ||
        !optionalCount(body.maxBitrate) || !optionalCount(body.maxHeight) || !count(body.offsetSeconds) ||
        !optionalText(body.errorCode, 80) ||
        !Array.isArray(body.causes) || body.causes.length > 8 || body.causes.some(c => !text(c, 120)) ||
        !count(body.sinceTuneMs) || typeof body.firstFrame !== 'boolean' || !count(body.bufferingMs) ||
        !count(body.positionMs) || !count(body.loads) || !count(body.loadedBytes))
      throw new Error('Invalid playback failure');
    const entry = {
      receivedAt: now, kind: body.kind, appVersion: body.appVersion, device: body.device, route: body.route,
      channelId: body.channelId ?? null, itemId: body.itemId ?? null, mediaSourceId: body.mediaSourceId ?? null,
      commercial: body.commercial, direct: body.direct ?? null,
      maxBitrate: body.maxBitrate ?? null, maxHeight: body.maxHeight ?? null, offsetSeconds: body.offsetSeconds,
      errorCode: body.errorCode ?? null, causes: body.causes,
      sinceTuneMs: body.sinceTuneMs, firstFrame: body.firstFrame, bufferingMs: body.bufferingMs,
      positionMs: body.positionMs, loads: body.loads, loadedBytes: body.loadedBytes,
    };
    const lines = [...this.read(now), entry].slice(-MAX_LINES);
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, lines.map(l => JSON.stringify(l)).join('\n') + '\n', { mode: 0o600 });
    fs.renameSync(tmp, this.file);
    return entry;
  }
  /** Newest first. */
  recent(limit = 200, now = Date.now()) {
    const bounded = Number.isSafeInteger(limit) && limit > 0 ? Math.min(limit, MAX_LINES) : 200;
    return this.read(now).reverse().slice(0, bounded);
  }
}
module.exports = { FailureLog, MAX_AGE_MS, MAX_LINES };
