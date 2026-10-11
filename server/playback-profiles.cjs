const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

/** Playback learning is independent of schedule generations; regeneration preserves it. */
class PlaybackProfiles {
  constructor(directory) { this.directory = directory; fs.mkdirSync(directory, { recursive: true }); }
  file(itemId, source, device) {
    for (const value of [itemId, source, device])
      if (typeof value !== 'string' || !value.length || value.length > 512) throw new Error('Invalid profile identity');
    return path.join(this.directory, crypto.createHash('sha256').update(JSON.stringify([itemId, source, device])).digest('hex') + '.json');
  }
  get(itemId, source, device) {
    const file = this.file(itemId, source, device);
    return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) :
      { itemId, sourceFingerprint: source, deviceProfile: device, observations: [], preferred: null };
  }
  observe(body) {
    const { itemId, sourceFingerprint, deviceProfile, outcome, settings, startupMs, playedMs, wallMs, encoderSpeed } = body;
    if (!['success', 'failure'].includes(outcome) || !settings || !Number.isSafeInteger(settings.maxBitrate) ||
        settings.maxBitrate < 100000 || settings.maxBitrate > 200000000 ||
        !Number.isSafeInteger(settings.maxHeight) || settings.maxHeight < 240 || settings.maxHeight > 4320 ||
        !Number.isSafeInteger(startupMs) || startupMs < 0 ||
        !Number.isSafeInteger(playedMs) || playedMs < 0 || !Number.isSafeInteger(wallMs) || wallMs < 0 ||
        (encoderSpeed != null && (!Number.isFinite(encoderSpeed) || encoderSpeed < 0)))
      throw new Error('Invalid playback observation');
    if (outcome === 'success' && (playedMs < 30000 || wallMs < 30000 || playedMs / wallMs < 0.9))
      throw new Error('Success requires 30 seconds of sustained playback');
    const record = this.get(itemId, sourceFingerprint, deviceProfile);
    const sample = { outcome, settings: { maxBitrate: settings.maxBitrate, maxHeight: settings.maxHeight },
      startupMs, playedMs, wallMs, encoderSpeed: encoderSpeed ?? null, measuredAt: Date.now(),
      playbackRatio: wallMs > 0 ? playedMs / wallMs : 0 };
    record.observations = [...record.observations, sample].slice(-32);
    if (outcome === 'success') record.preferred = sample.settings;
    else if (JSON.stringify(record.preferred) === JSON.stringify(sample.settings)) record.preferred = null;
    const file = this.file(itemId, sourceFingerprint, deviceProfile);
    const tmp = file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(record), { mode: 0o600 }); fs.renameSync(tmp, file);
    return record;
  }
}
module.exports = { PlaybackProfiles };
