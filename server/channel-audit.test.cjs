const { test } = require('node:test');
const assert = require('node:assert/strict');
const { auditChannels } = require('./channel-audit.cjs');
const { auditWindow } = require('./channel-audit.cjs');
const channels = [{ id: 1 }, { id: 2 }, { id: 3 }];
const day = (channel, ids) => ({ channelId: channel, dayKey: '2026-10-10',
  blocks: ids.map(id => ({ itemId: id, isBumper: false })) });

test('reruns and split programme segments count once; bumpers do not count', () => {
  const a = day(1, ['a', 'a', 'b', 'c']);
  a.blocks.push({ itemId: 'bumper-only', isBumper: true });
  const report = auditChannels(channels, [a, day(2, ['a', 'b', 'c'])]);
  assert.equal(report.pairs.length, 1);
  assert.equal(report.pairs[0].sharedItems, 3);
  assert.equal(report.pairs[0].candidate, 'near-duplicate');
  assert.equal(report.pairs[0].jaccard, 1);
});

test('a small channel contained in a larger channel is not labelled near-duplicate', () => {
  const report = auditChannels(channels, [day(1, ['a', 'b', 'c']), day(2, ['a', 'b', 'c', 'd', 'e', 'f'])]);
  assert.equal(report.pairs[0].candidate, 'mostly-contained');
  assert.equal(report.pairs[0].uniqueB, 3);
  assert.equal(report.pairs[0].jaccard, .5);
});

test('movie versions match by identity and low evidence does not recommend merging', () => {
  const a = day(1, ['version-a']), b = day(2, ['version-b']);
  a.blocks[0].movieIdentity = b.blocks[0].movieIdentity = 'tmdb:123';
  assert.equal(auditChannels(channels, [a, b]).pairs.length, 0);
  assert.equal(auditChannels(channels, [a, b], 1).pairs[0].sharedItems, 1);
});

test('different episodes of a series count as 60 percent overlap over two hours', () => {
  const catalog = { a: { type: 'episode', title: 'Show', seriesId: 's' },
    b: { type: 'episode', title: 'Show', seriesId: 's' } };
  const manifests = [1, 2].map((id, i) => ({ channelId: id, blocks: [
    { itemId: i ? 'b' : 'a', startUnix: 0, endUnix: 4320, isBumper: false }] }));
  const report = auditWindow(channels, manifests, 0, catalog);
  assert.equal(report.pairs[0].overlap, .6);
  assert.equal(report.pairs[0].sharedMinutes, 72);
  assert.equal(auditWindow(channels, manifests, 1, catalog).pairs.length, 0);
});

test('off-air time and bumpers cannot inflate similarity', () => {
  const manifests = [1, 2].map(id => ({ channelId: id, blocks: [
    { itemId: 'a', startUnix: -100, endUnix: 1000, isBumper: false },
    { itemId: 'a', startUnix: 1000, endUnix: 7200, isBumper: true }] }));
  assert.equal(auditWindow(channels, manifests, 0).pairs.length, 0);
});
