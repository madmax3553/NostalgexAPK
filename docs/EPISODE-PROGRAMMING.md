# Ordered series programming

This is the agreed direction for StaticGroot, not an implemented schedule mode.
The current seeded ordering shuffles episodes within each series before taking
pairs. It therefore does not preserve story order.

## Station-wide first-run timetable

- Allocate different fixed local-time slots to different series across the station,
  rather than placing every series' new episodes at the same time. For example,
  Friends at 15:00 and Time Trax at 16:00 if their reserved durations fit.
- Give each series one home channel for first-run blocks. Other eligible channels
  carry reruns, not simultaneous premieres of the same series.
- Default to the next two available episodes in numeric season/episode order.
  Missing episodes are recorded as gaps, not silently invented. Specials require
  a separate ordering policy.
- A back-to-back pair is one scheduling unit. Its opening is hourly and the second
  episode keeps the existing :00/:30 alignment with commercials between episodes.
  Never shuffle or time-shift one episode of a pair independently.
- Reserve the actual aligned duration of the complete block. Two 45-minute episodes
  need more than one hour. Do not truncate the second episode to fit another slot.
- Published programme times remain immutable unless explicitly regenerated.
- A limited daily first-run window cannot contain every series in a large library.
  Use a weekly roster for overflow rather than creating conflicting first-run slots.
  Window, roster selection and manual slot controls are not yet chosen.

## Persistent progression

### Prime-window freshness (pending)

Keep a server-owned rolling airing ledger derived from published schedules, not
device watch sessions. Track last aired, last prime-window aired and recent counts
by programme/series and channel, with station-wide exposure available as a separate
score. Treat overnight reruns separately so they do not unnecessarily disqualify a
programme from an evening slot. Prefer least-recently-exposed eligible titles in
prime windows, relaxing repeat spacing when a small library has no alternatives.
Published future slots remain locked. Regeneration replaces the ledger contribution
for that generation rather than double-counting old and replacement schedules.
Prime windows must be configurable in the station time zone; their defaults have
not been chosen. This freshness policy is not implemented yet.

The companion needs stable Jellyfin series IDs and numeric season/episode fields,
plus separate first-run and rerun cursors per series. The existing display `SxxExx`
tag is not sufficient identity for persistence.

First-run means first aired on StaticGroot, independently of Jellyfin's watched
flag. Allocate first-run episode pairs when publishing a day, atomically with the
manifest. Looking ahead in the guide or playback on a second TV must not advance
the cursor. Explicit regeneration must reuse allocated episode pairs unless the
user explicitly resets progression.

Reruns use their own cursor through already-aired episodes in order, still in
pairs. When exhausted, begin another ordered cycle. Keep the already-published
day intact when new episodes arrive.

## Channel consolidation audit

`server/channel-audit.cjs` compares distinct aired items in the active published
manifests. It ignores commercials, bumper references, repeat frequency and
post-break continuation segments. It reports:

- Jaccard overlap: shared items divided by the union.
- Directional coverage: how much of each channel appears on the other.
- Unique item counts for each channel.
- Near-duplicate recommendation only when both coverages reach 90% and at least
  three distinct items are shared.
- Mostly-contained recommendation when one coverage reaches 95%.

This compares scheduled items, not the full eligible library pools. The full-pool
audit is still needed before merging channels; a three-day sample can overlook
content not scheduled yet. Different genres, ratings, time restrictions and
editorial purposes also need review. No channels are automatically merged.

### Two-hour viewing overlap

The short-window audit flags 60% shared series/movie airtime over a full two-hour
window (72 minutes). Different episodes of the same series count together through
their Jellyfin series ID. It sums each series' clipped airtime on both channels
and takes the smaller total, so it detects similar programming even when the
episodes are offset rather than playing simultaneously. Bumpers and off-air time
do not contribute. The denominator remains the full two hours, preventing two
mostly-empty channels from looking like duplicates.

The Android client uploads an item-to-series catalog independently of the published
schedule, allowing existing day files to be audited without regeneration. A report
with unresolved metadata is provisional; IDs alone cannot detect different episodes
of the same show.

Pass a Unix window start as the second argument:

```sh
node server/channel-audit.cjs /data/<station-id> <window-start-unix>
```

This is a recommendation threshold, not automatic consolidation. Check multiple
windows and unique programming before removing a channel.

### Generation-time reshuffling

Android generation now runs a bounded overlap-reduction pass before publication.
It evaluates twelve two-hour windows per local day at the 60% shared-airtime
threshold. Only equal-duration complete series/programme runs are swapped, retaining
internal episode order, midpoint continuation offsets and commercials. TV runs
move only to hourly starts, premiere channels remain fixed, and published channels
are locked scoring references. Explicit regeneration may optimize replacement days.
This conservative heuristic cannot fix overlap when there are no alternative runs
of compatible duration. It does not implement the pending ordered-series planner.

Run on a station's server data directory:

```sh
node server/channel-audit.cjs /data/<station-id>
```

Keep reports derived from a personal library local. Public examples and tests use
synthetic items only.
