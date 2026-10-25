const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { StationStore, createServer } = require('./schedule-service.cjs');
const station = 'a'.repeat(64);
const setup = { timeZone: 'UTC', channels: [{ id: 1, name: 'Films', enabled: true }] };
const block = { itemId: 'm1', startUnix: 100, endUnix: 3700, mediaOffsetSeconds: 0,
  isBumper: false, isMidBreak: false, movieIdentity: 'movie:1' };
function directory(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'staticgroot-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true })); return dir;
}
test('published times survive restart and a differing second device proposal', t => {
  const dir = directory(t); const first = new StationStore(dir);
  first.setup(station, setup);
  const day = first.day(station, 1, '2026-10-10', { blocks: [block] });
  const second = new StationStore(dir);
  assert.deepEqual(second.day(station, 1, '2026-10-10', { blocks: [{ ...block, startUnix: 200 }] }), day);
});
test('adjacent movie repeats rejected, continuation and separated repeats permitted', t => {
  const store = new StationStore(directory(t)); store.setup(station, setup);
  assert.throws(() => store.day(station, 1, '2026-10-10', { blocks: [block,
    { ...block, startUnix: 3700, endUnix: 7300 }] }), /repeated/);
  assert.equal(store.day(station, 1, '2026-10-10', { blocks: [block,
    { ...block, startUnix: 3700, endUnix: 7300, mediaOffsetSeconds: 3600 }] }).blocks.length, 2);
});
test('force regeneration publishes a complete new generation and preserves the previous one', t => {
  const dir = directory(t); const store = new StationStore(dir); store.setup(station, setup);
  store.day(station, 1, '2026-10-10', { blocks: [block] });
  const changed = { ...block, itemId: 'm2', movieIdentity: 'movie:2' };
  const updated = store.regenerate(station, { generation: 0, days: [
    { channelId: 1, dayKey: '2026-10-10', blocks: [changed] }] });
  assert.equal(updated.generation, 1);
  assert.equal(store.day(station, 1, '2026-10-10').blocks[0].itemId, 'm2');
  assert.ok(fs.existsSync(path.join(dir, station, '1_2026-10-10.json')));
  assert.throws(() => store.day(station, 1, '2026-10-11', { generation: 0, blocks: [block] }), /restart/);
});
test('invalid regeneration cannot change the active schedule', t => {
  const store = new StationStore(directory(t)); store.setup(station, setup);
  store.day(station, 1, '2026-10-10', { blocks: [block] });
  assert.throws(() => store.regenerate(station, { generation: 0, days: [
    { channelId: 1, dayKey: '2026-10-10', blocks: [{ ...block, endUnix: 0 }] }] }));
  assert.equal(store.setup(station).generation || 0, 0);
  assert.equal(store.day(station, 1, '2026-10-10').blocks[0].itemId, 'm1');
});
test('channel setup updates are shared and version conflicts rejected', t => {
  const store = new StationStore(directory(t)); const saved = store.setup(station, setup);
  store.updateSetup(station, { ...saved, channels: [{ ...saved.channels[0], enabled: false }] });
  assert.equal(store.setup(station).channels[0].enabled, false);
  assert.throws(() => store.updateSetup(station, saved), /reload/);
});
test('generated channels append once, publish days, and never rewrite existing ones', t => {
  const store = new StationStore(directory(t)); store.setup(station, setup);
  const gen = { id: 1000007, name: 'STAR TREK', enabled: true, definition: { id: 1000007, number: 300, name: 'STAR TREK', colorHex: '#fff' } };
  const added = store.addChannels(station, { channels: [gen] });
  assert.equal(added.version, 2);
  assert.deepEqual(added.channels.map(c => c.id), [1, 1000007]);
  // A second device proposing the same id with another name changes nothing, not even the version.
  const again = store.addChannels(station, { channels: [{ ...gen, name: 'OTHER', definition: { ...gen.definition, name: 'OTHER' } }] });
  assert.equal(again.version, 2);
  assert.equal(again.channels[1].name, 'STAR TREK');
  // Enabled flags still follow the existing update path, and the new channel can publish a day.
  store.day(station, 1000007, '2026-10-10', { blocks: [block] });
  assert.equal(store.day(station, 1000007, '2026-10-10').blocks[0].itemId, 'm1');
});
test('generated channels cannot take static ids or carry malformed definitions', t => {
  const store = new StationStore(directory(t)); store.setup(station, setup);
  const ok = { id: 1000001, name: 'X', definition: { id: 1000001 } };
  assert.throws(() => store.addChannels(station, { channels: [{ ...ok, id: 5, definition: { id: 5 } }] }), /Invalid channel/);
  assert.throws(() => store.addChannels(station, { channels: [{ ...ok, definition: { id: 2 } }] }), /Invalid channel/);
  assert.throws(() => store.addChannels(station, { channels: [{ ...ok, name: '' }] }), /Invalid channel/);
  assert.throws(() => store.addChannels(station, { channels: 'x' }), /Invalid channels/);
  assert.equal(store.setup(station).channels.length, 1);
});
test('HTTP rejects missing auth when token is configured and publishes authenticated requests', async t => {
  const token = 'synthetic-test-key-1234567890';
  const server = createServer({ directory: directory(t), token });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const url = `http://127.0.0.1:${server.address().port}/v1/stations/${station}/setup`;
  assert.equal((await fetch(url)).status, 401);
  const response = await fetch(url, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: JSON.stringify(setup) });
  assert.equal(response.status, 200);
});
test('HTTP allows tokenless station discovery and replaces an empty day proposal', async t => {
  const server = createServer({ directory: directory(t) });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${server.address().port}/v1/stations/${station}`;
  assert.equal((await fetch(`${base}/setup`, { method: 'POST', body: JSON.stringify(setup) })).status, 200);
  await fetch(`${base}/days/1/2026-10-10`, { method: 'POST', body: JSON.stringify({ blocks: [] }) });
  const populated = await fetch(`${base}/days/1/2026-10-10`, { method: 'POST', body: JSON.stringify({ blocks: [block] }) });
  assert.equal(populated.status, 200);
  assert.equal((await populated.json()).blocks.length, 1);
});
