const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { PlaybackProfiles } = require('./playback-profiles.cjs');
const sample = { itemId: 'movie-1', sourceFingerprint: 'etag-v1', deviceProfile: 'hevc-main10-v1',
  outcome: 'success', settings: { maxBitrate: 4000000, maxHeight: 720 }, startupMs: 2000,
  playedMs: 31000, wallMs: 32000 };
function store(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'groot-profiles-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return { dir, profiles: new PlaybackProfiles(dir) };
}
test('verified settings survive restart and are reused by a matching device class', t => {
  const { dir, profiles } = store(t); profiles.observe(sample);
  const saved = new PlaybackProfiles(dir).get(sample.itemId, sample.sourceFingerprint, sample.deviceProfile);
  assert.deepEqual(saved.preferred, sample.settings);
  assert.equal(saved.observations[0].encoderSpeed, null);
});
test('changed media and different capabilities do not inherit old settings', t => {
  const { profiles } = store(t); profiles.observe(sample);
  assert.equal(profiles.get(sample.itemId, 'etag-v2', sample.deviceProfile).preferred, null);
  assert.equal(profiles.get(sample.itemId, sample.sourceFingerprint, 'h264-only').preferred, null);
});
test('a failed preferred setting is invalidated and short playback cannot become trusted', t => {
  const { profiles } = store(t); profiles.observe(sample);
  const failed = profiles.observe({ ...sample, outcome: 'failure', playedMs: 0 });
  assert.equal(failed.preferred, null);
  assert.throws(() => profiles.observe({ ...sample, playedMs: 5000, wallMs: 6000 }), /30 seconds/);
});
