# Dev log

Why things changed, and what state they were left in. Newest first.
Commits carry the detail of *what*; this carries the *why*.

## 2026-10-12 (service deployed, station break audit)

**Shipped.** Companion image `staticgroot-schedules:0.1.22` replaced 0.1.19 on
the Jellyfin Docker host, same data volume and token. The volume was backed up
to a tarball on the host first, and the previous container is kept stopped as
`staticgroot-schedules-old` for rollback. The test TV then registered its
generated channels through `POST /setup/channels` and published days for them.
StaticGroot 0.1.23 (24) debug APK installed on the TV.

**Measured.** Published days (530 channel-days, generation 3) before this
change: station breaks were 13.8 percent of airtime. Between programmes: 5,282
breaks, median 9 min, p90 16, p99 39, max 60; 213 of them 20 minutes or longer.
Every one of those 213 ended on a :00 or :30 and followed a TV episode. Cause:
TV starts snap to the next half hour (hourly for the first of a repeated show
block), so a 35 to 50 minute episode waited for the next :00 or :30, and a
55 to 62 minute episode plus the 30 second minimum rounded up to 30 or 60
minutes. Movies were bounded by their quarter hour grid at 15. The long ones
clustered on streaming-network channels, whose episodes run long.

**Changed.** `StationTiming` caps a break at 10 minutes. The next programme
tries its preferred grid (hourly for a show block), then the base grid (half
hour for TV, quarter hour for movies), then the next 5 minute mark, and takes
the first that waits 10 minutes or less. Programmes that end soon enough still
land on the grid, so the schedule re-syncs. This is a deliberate reversal of the
earlier "TV uses half hours regardless of wait length" test, which is rewritten.
A repeated show block no longer forces an hourly start when that costs more than
10 minutes. Android-only: tvOS has no station breaks.

**Verified.** 230 Android tests pass, and one of them checks every possible end
time in a day, for each media type and block mode, gives a break between 30
seconds and 10 minutes. Lint passes. Not measured: the new break distribution
on a real library. A replay of the published sequences through both rules
overstated the old breaks (median 16 against the real 9, because block lengths
are a crude proxy for movie versus TV), so no before and after comparison is
claimed beyond the cap.

**Half-done.** Published days are immutable, so nothing changes until a day is
not yet published or the schedule is force regenerated from Settings, which makes
a new generation (old ones are kept) and other TVs must restart. About 0.2
percent of airtime is the fill from the last programme to midnight, up to 51
minutes on 112 of 530 days; not changed.

**Next.** Force regenerate on the test TV and re-measure from the service data.

## 2026-10-12 (planner tightened after the first TV run)

**Measured.** First run on a test TV against a library of 2,656 items (5
sections): the planner proposed SCREAM (10 items), a 4-item Rambo franchise, a
1,409-episode DRAMA TV and an 808-episode SCI-FI TV. DRAMA TV duplicated the
fixed DRAMA TV channel, and the overlap guard did not catch it because the fixed
channel has exclusions. No Star Trek channel: that library does not hold enough.

**Changed.** Drama and Comedy never get a generated genre channel (too broad,
already covered). A franchise needs at least 600 minutes, not 300, so four short
films no longer repeat all day. After this, the same library proposes SCREAM
(10) and SCI-FI TV (133). The 600 minute and broad genre list are judgement
from one library, not tuned across several.

**Also.** When the service cannot register generated channels (HTTP 405 from the
old image), the app now falls back to the static lineup less off-season
bundles, instead of dropping the seasonal channels too. The service on the test
setup is still the old image, so generated channels are not shown there yet.

**Verified.** Android tests and lint pass; installed on the test TV (0.1.22).
Enrichment did not run on that build: no TMDB key was configured.

**Next.** Redeploy the service image so `/setup/channels` exists. Add TMDB and
OMDb keys to `android/local.properties` and read the `ENRICHMENT delta` line.

## 2026-10-12 (library channels, one seasonal channel, guide wrap)

**Shipped.** Nothing. StaticGroot 0.1.22 (23) builds and passes tests but has not
been installed on a TV. The shared service needs the new image (below) before
generated channels can sync.

**Changed.**
- Bundles are not being ported. On Android every channel in `channels.json` was
  always on, including the Christmas and Scream ones in July. Now the channels of
  any bundle with `activeMonths` leave the lineup, and in season one channel
  takes their place, holding the union of their items and numbered where the
  first of them sat. Season follows tvOS `isInSeason` (September from the 15th).
  The repeat allowance needed no scheduler change: `packDay` already loops a
  pool and `withBumpers` spaces repeats, so a small seasonal pool repeats more.
- Channels derived from the library (`LibraryChannelPlanner`, Android only, not
  in tvOS): a franchise when titles share a leading phrase ("Star Trek" over its
  series and films), one channel for a series of 50 or more episodes, and a genre
  channel per type when a genre is at least 12 percent of that type and holds a
  day of content (movies) or 60 episodes. Guards, each with a test: a shared
  first word alone is not a franchise ("American ..."), filler phrases are not
  ("I Am ..."), a one-word franchise needs a sequel marker ("Rocky", "Rocky
  II"), nested phrases resolve to the shorter one, franchise items are removed
  from genre pools so they do not repeat, kids, horror, documentary and
  reality genres never get one, and a candidate that covers 80 percent or more
  of an existing channel is skipped. At most 12 generated channels.
- Generated channels keep their id (a hash of the spec, from 1,000,000), number
  (from 300, first free) and name in `generated_channels.json`, and stay while
  they hold 3 items, so a library change does not make channels flicker.
- Shared station: the service refused days for channels not in the saved setup,
  and setup could not grow. New `POST /v1/stations/{station}/setup/channels`
  appends channels with ids from 1,000,000 and never changes existing ones, so
  every TV ends up with the same names and numbers. If the service is older and
  refuses, generated and seasonal channels are left out and the launch continues.
- Guide wrap: Down already wrapped the selection. The window now scrolls through
  the seam, so channel 1 appears under the last channel instead of the list
  jumping to the top. Up from channel 1 still goes to Settings, as the tvOS
  tests require. The arithmetic is in `GuideNav` with tests.

**Verified.** 227 Android tests (was 198) and 16 server tests (was 14) pass.
Assembly and lint pass; both channel smoke scripts pass. Server README has the
new endpoint.

**Not verified.**
- Nothing has run on a real library. The planner's thresholds (12 percent, 50
  episodes, 300 minutes, overlap 80 percent) are judgement, not measured. The
  `LINEUP` log line names every generated channel and its size: that is the
  measurement to take, and false positives such as an accidental franchise are
  the thing to look for.
- The wrap is verified as arithmetic, not on a remote. The report that it did not
  wrap could not be reproduced from the code, so if it still misbehaves on the TV
  the cause is elsewhere.
- Generated channels have not met a real shared service, and the overlap
  reducer treats them like any channel.
- Swift untouched and not run.

**Half-done.** No TV-side screen to turn generated channels on or off; the
`enabled` flag on the service is honoured but only settable through its API.
Franchise detection uses titles only, with no TMDB collections.

**Next.** Deploy the service image with the new endpoint, run a debug build and
read the `LINEUP` line. A person needs to say whether a generated channel
should ever be removable from the TV.

## 2026-10-11 (enrichment, music genres, multi-part movies)

**Shipped.** Nothing. StaticGroot 0.1.21 (22) builds and passes tests but has not
been installed on a TV, and no TMDB or OMDb key was available, so the enrichment
and music-lookup network paths have never run against the real services.

**Changed.**
- Rule filter enrichment was never wired in. `MainActivity` called `buildAll`
  with no enrichment, and the lookup was keyed by Jellyfin item id where tvOS
  keys by TMDB id (episodes share their show's record). Every keyword, network,
  production-company and OMDb rule therefore matched nothing. Now ported from
  tvOS `EnrichmentService`/`TMDBService`/`OMDBService` into `enrichment/`, keyed
  by TMDB id, with an IMDb id on `MediaItem` for the OMDb lookup.
- Static count from `channels.json`: 6 of 131 channels cannot fill without
  enrichment (BIOPICS, FOREIGN FILMS, MIDNIGHT, CULT CLASSICS, TOON TOWN, SPY
  GAMES), 3 more are keyword-gated, and 35 take enrichment as an extra OR
  criterion. The other 87 do not depend on it. Earlier estimates that counted
  `studios` overstated this: that field is native Jellyfin data.
- No Supabase layer. A JSON cache in app storage stands in for it, keyed by TMDB
  id. Two differences from tvOS follow from that: a 90 second budget per launch
  (the remainder is picked up on later launches, saved every 250 titles), and
  OMDb is not re-asked for 30 days about a title it already answered, so titles
  with no rating do not spend the free daily quota on every launch.
- Keys come from `local.properties` or the `TMDB_API_KEY` and `OMDB_API_KEY`
  environment variables into `BuildConfig`. Empty means skip, as on tvOS. A key
  compiled into an APK can be read back out of it, same as tvOS Info.plist.
- Music videos: ported `MusicTitleParser` and `MusicYear` so title and artist are
  split from ripped filenames, and the guide, info panel, up-next and coming-next
  show "Artist · Song".
- MusicBrainz and Deezer ported (`MusicBrainzClient`, `DeezerClient`,
  `MusicEnrichmentService`), with the tag and genre vocabulary maps and the 1 per
  second and 4 per second spacing tvOS uses. At about 2 MusicBrainz requests per
  video a large library takes tens of minutes, so the pass runs on one background
  thread after the guide opens (tvOS also defers it past tune-in), saves every 25
  rows, and its results are applied on the next launch. Cached rows are applied
  to items before the pools build, so genre and decade channels fill from them.
  Differences from tvOS: a lookup that fails (as opposed to finding nothing)
  caches no row, where tvOS pins a degraded row for 90 days, and five failures in
  a row end the pass; Deezer's checked stamp is set only when Deezer answered.
- A music library is asked for `MusicVideo` items as well as `Movie` only when the
  server reports at least one. tvOS requests `Movie` only, so this is not parity:
  an earlier note here called it a gap, and that was an inference. With none
  present the request is identical to tvOS. Unmeasured on a real server.
- Multi-part movies: ported `MultiPartStack`. A movie whose sources all carry
  distinct part markers is scheduled at the summed runtime and plays disc to
  disc. A tune-in offset is mapped into the right disc, and the mid-break check
  adds the disc's start so a second disc is not read as a position near zero.
  Other multi-source items now play the highest-bitrate source (tvOS
  `bestMediaSource`) instead of whichever came first. Single-source items send
  the same PlaybackInfo request as before.
- Back in the guide now goes to the playing channel, then leaves the app (tvOS
  `GuideExitAction`). It used to open Settings every time, so Home was the only
  way out. Settings stays on Menu and the button above the first row.
- A Keystore failure no longer crashes the app. `SessionStore` and the companion
  settings go through `SecurePrefs`, which clears an unreadable file and, if the
  master key itself is bad, recreates it. Result is a sign-in, not a crash loop.
- Removed `PlaybackLog.kt`: unused, and it logged exception text that can carry
  token-bearing URLs.
- CI gets `android-unit-tests` and `server-tests` jobs. Docs: `server/README.md`
  had an empty API heading and an incomplete endpoint table; `build.gradle` is
  `build.gradle.kts` in CLAUDE.md and the README.

**Verified.** 198 Android unit tests (was 126) and 14 server tests pass. Debug
assembly and lint pass. `nostalgex-filter-smoke.mjs` 53 of 53 and
`nostalgex-schedule-order-smoke.mjs` 12 of 12. New tests carry the tvOS cases for
the music parser, year rules, `MultiPartStack` and `GuideExitAction` as written.
Gradle needs JDK 17: with the machine default (JDK 27) it fails with just "27".

**Not verified.**
- Real-library effect of enrichment. Debug builds log
  `ENRICHMENT delta: channelsWithItems +N, channelsWithDifferentPool=M` after the
  first keyed launch. That is the measurement to take; no number exists yet.
- TMDB and OMDb live behaviour, including how many launches a large library
  needs to finish, and whether the 90 second budget is right on a TV.
- Swift tests were not run (no macOS here). The Swift side is unchanged.
- Multi-part playback on a real stacked movie, and the CI workflow itself.
- Parity deviation: on tvOS the Jellyfin music path reads the filename year from
  the already-cleaned title, which has lost it. Android reads the raw name, so a
  "(1988)" suffix now wins as the Swift tests describe. Not confirmed against
  Swift on the same input.

**Half-done.** The music pass has no live run: how long it takes, how many of a
real library it names, and what share of titles end with a useful genre are all
unmeasured (tvOS measured 47 of 60 resolved by Deezer on one library). Its
results show up one launch late by design. Version picking is automatic only.
Bundles, Emby and Plex are not started.

**Next.** Run a debug build with keys against a real library and record the
enrichment delta line and the `MUSIC done` line here. A person needs to supply
TMDB and OMDb keys and decide whether the Android privacy text should mention
that titles and artist names go to TMDB, OMDb, MusicBrainz and Deezer before a
public build.

## 2026-10-11 (session checkpoint)

**Changed.** Committing the work through StaticGroot 0.1.20 (21): left-aligned
GROOT header, protected programme endings, series catalog and overlap audits,
generation-time block-preserving reshuffling, and the ordered-series programming
design. The APK was installed on the test TV during development; the companion
catalog endpoint was deployed previously. This checkpoint does not change the
running service or regenerate published schedules.

**Verified.** 126 Android unit tests and 14 server tests pass. Debug assembly and
lint passed during the generation update. Diff whitespace checks pass.

**Half-done.** Rolling prime-window freshness, server-owned series progression,
autonomous horizon extension and adaptive transcoding remain pending. Generation
reshuffling has synthetic regression coverage but its real-library improvement
has not been measured. Complete programme-end verification is still needed.

**Next.** Implement the server airing ledger and prime-window exposure scoring,
then the staggered first-run planner documented in `docs/EPISODE-PROGRAMMING.md`.

## 2026-10-10 (generation overlap reduction and freshness follow-up)

**Changed.** StaticGroot 0.1.20 (21) performs a generation-only overlap-reduction
pass using the 60% shared-airtime threshold in two-hour windows. It swaps complete
equal-duration series/programme runs, keeping episode order inside each run,
commercials, continuation offsets and clock alignment. TV runs move only to hourly
starts and premiere channels stay anchored. Existing published days are fetched
first and locked; new days can be optimized against them. Explicit regeneration
optimizes the replacement horizon before publishing. No automatic merging.

**Verified.** Assembly and 126 Android unit tests pass. Synthetic tests verify
an overlapping pair moves intact, continuous timing and locked published references.
This bounded heuristic is not guaranteed to find every improving arrangement;
it only swaps compatible-length runs and samples twelve fixed windows each day.

**Half-done.** Rolling prime-window freshness remains a documented follow-up in
`docs/EPISODE-PROGRAMMING.md`. Existing history is local/yesterday-based, not a
server-owned exposure policy. The required ledger should derive from published
schedules, separate prime-window exposure from overnight reruns and avoid counting
both old and regenerated allocations. Ordered first-run progression is still pending.
Changes are uncommitted on `master`; actual overlap reduction on the TV has not
been measured and published days remain unchanged until explicit regeneration.

**Next.** Build the server airing ledger, configure station prime windows and use
least-recently-exposed eligible titles there without shifting published promises.

## 2026-10-10 (two-hour series-airtime overlap)

**Shipped.** StaticGroot 0.1.19 (20) debug APK installed and companion image
`staticgroot-schedules:0.1.19` deployed retaining station data. Changes are
uncommitted on `master`.

**Changed.** The viewing-overlap audit flags 60% shared programming across two
hours (72 minutes), grouped by series rather than episode ID. A catalog uploaded
by Android carries stable Jellyfin series IDs. Airtime is clipped to the window;
breaks and off-air time are excluded, with the full two-hour denominator retained.
The report measures shared programming mix, not necessarily simultaneous episode
collisions. Existing published times are not changed by catalog upload or audit.
No channels are merged automatically.

**Verified.** Five audit tests cover series identity across different episodes,
the exact 60% threshold, clipping, off-air exclusion and the original long-window
metrics. Fourteen server tests and 124 Android tests pass; debug assembly succeeds.
The deployed audit used the uploaded catalog with no unresolved item identities.
The pair reported by the user did not meet the threshold in the later window
sampled; the original observation window has not been reproduced. Personal
library-derived report contents are not copied into this public log.

**Next.** Sample successive two-hour windows and review consistently overlapping
channels before consolidation. Ordered first-run slots and episode pairs remain
unimplemented.

## 2026-10-10 (ordered series programming direction and overlap audit)

**Changed.** `docs/EPISODE-PROGRAMMING.md` records the next scheduling model:
staggered station-wide first-run series slots, one home channel per series,
ordered episode pairs, separate first-run/rerun cursors and no independent shifting
of an episode inside a pair. Pair slots retain :00/:30 alignment. Publishing a
future day must allocate progression once; viewing, another device or regeneration
must not accidentally consume the next pair. The window and overflow roster still
need decisions. This model is documented, not implemented.

`server/channel-audit.cjs` adds a read-only overlap report for active published
manifests. It counts unique aired items, excluding bumps, repeat frequency and
continuation segments. Near-duplicate recommendations require 90% coverage both
ways and at least three shared items. Three synthetic audit tests pass. An audit
was run on the deployed service through a temporary script; no station files or
channel settings were changed. Personal-library counts and report contents are
not copied into this public log.

**Half-done.** The report measures scheduled overlap, not full eligible pools.
Episode order still uses the existing shuffle. Progression cursors, series IDs,
the staggered timetable and first-run home-channel allocation remain to build.
Changes are uncommitted on `master`.

**Next.** Implement stable series identity and ordered pairs, then a server-owned
staggered slot planner and progression transactions. Review full-pool overlap
before proposing channel consolidation.

## 2026-10-10 (do not truncate programme endings)

**Shipped.** StaticGroot 0.1.18 (19), debug APK installed on the test TV.
Assembly, lint and 124 Android tests pass. Changes are uncommitted on `master`.

**Measured.** A programme transitioned to a bumper at media position 2,460 seconds
(41 minutes) with no preceding natural-ended state. The tick loop treated the final
scheduled segment's estimated end as a mandatory media cutoff, just like a planned
midpoint intermission. This could discard remaining content when metadata and
actual file duration differed, or a stored block was shorter than the file.

**Changed.** Only an explicit midpoint break for the same item can interrupt media
at a scheduled offset. Final segments wait for Media3's natural end. Late completion
consumes inter-programme bumper time instead of truncating the ending. Three
regression checks cover a synthetic 41-minute estimate with three minutes of actual
content left, the intentional midpoint boundary and filler consumption.

**Half-done.** The reported episode's full ending has not been observed after this
fix. Published schedule estimates are unchanged; if a file overruns all available
filler, playback can run late rather than cut the content. Accurate runtime and
cross-device late-playback reconciliation remain follow-up work.

**Next.** Verify the complete episode ending and subsequent bumper on hardware.

## 2026-10-10 (header logo alignment)

**Changed.** StaticGroot 0.1.17 (18) left-aligns the GROOT header mark with the
programme information instead of centering it in the available navigation width.
The shared renderer accepts alignment explicitly; station bumpers retain their
centered composition. Changes are uncommitted on `master`.

**Shipped.** Debug APK built and installed on the test TV. Visual confirmation
is pending.

**Next.** Check header alignment on the TV.

## 2026-10-10 (playback controls and session commit)

**Changed.** StaticGroot 0.1.16 (17) adds a D-pad playback menu with Pause/Play,
Return to live and a saved captions toggle. Fullscreen OK or Up opens the menu;
Settings also exposes it. The Play/Pause remote key toggles playback directly.
Pause holds the station-break countdown as well as video, and resume excludes
the paused interval from playback-learning measurements. Return to live retunes
at the current station-clock position. Captions enable available Media3 text
tracks with the device language preference; server-side subtitle burning and
external subtitle acquisition are not implemented.

**Shipped.** Debug APK 0.1.16 (17) installed on the test TV. Assembly, lint,
121 Android tests and nine server tests pass.

**Half-done.** Hardware verification of pause, live return and captions is pending.
All accumulated Android tuner, station scheduling, companion deployment and
playback-learning work is being committed and pushed to `master` under Batman.
Adaptive quality stepping and encoder-speed collection remain unfinished.

**Next.** Verify controls and actual subtitle availability on the TV, then finish
the adaptive playback work using the stored observations.

## 2026-10-10 (persistent playback learning and malformed media investigation)

**Shipped.** StaticGroot 0.1.15 (16) debug APK installed, companion
`staticgroot-schedules:0.1.15` deployed retaining its data volume. Changes remain
uncommitted on `master`.

**Changed.** Playback profiles persist separately from schedule generations. Keys
include Jellyfin item, source freshness (ETag/source/size/runtime) and decoder class.
Android records startup, playhead progress, wall time and attempted bitrate/height.
Settings become preferred after at least 30 seconds of near-real-time playback;
failures invalidate matching preferences. Matching profiles are retrieved on later
negotiation. Media changes and different decoder classes do not inherit stale
results. Sources with no ETag are not reusable. No tokens or stream URLs are stored
in profiles. FFmpeg encoder speed remains null until a separate collector exists.

**Measured.** A reported failing 720p HEVC/AAC fixture has frame-RPS errors and
an invalid EBML number at the same file offset across server attempts, often with
zero output frames. A direct attempt on the TV also returned an I/O failure. This
is evidence of malformed source/container data, not just a slow encode. Another
item initially examined was identified as a different title and excluded from
that diagnosis. No source file was modified.

**Verified.** 121 Android tests and nine server tests pass. Profile tests cover
restart persistence, capability/source separation, rejecting short success samples
and invalidating failed preferences. Assembly and lint pass (lint before the final
failure-cache adjustment). Deployment and APK installation succeeded.

**Half-done.** Adaptive quality stepping, background library probing, encoder speed
collection and real-device profile persistence verification remain pending. Time
Trax is diagnosed but not repaired; lowering output quality cannot fix corrupt
input data. PlaybackInfo remains necessary for current source/session validation.

**Next.** Verify profile records on the server after a sustained TV session and a
failure, then wire encoder measurements and quality selection. Investigate a clean
source or non-destructive remux of the malformed fixture with explicit approval.

## 2026-10-10 (UHD seek and GPU investigation)

**Measured.** The failing UHD conversion uses software `libx264`, two threads,
HDR-to-SDR tone mapping and a 2560-pixel scale. The inspected FFmpeg command has no
`-ss` and starts HLS numbering at zero. It generated 22.60 seconds in 75.69 seconds
(0.299x real time). Client seeking to a later segment can therefore worsen startup;
even an efficient server seek would not fix the below-real-time throughput.

The Docker host GPU is NVIDIA GK107M, GeForce GT 640M Mac Edition. It predates
hardware HEVC decoding. The container has the render device mapped, but the VA-API
probe fails loading the nouveau video driver. No working GPU acceleration was
established. No server configuration or app playback behaviour was changed.

**Next.** Test a pre-converted compatible version or an explicitly lower-resolution
transcode on the same input. Measure startup and sustained play separately. A proper
seek path and a fast enough encode are both needed for scheduled UHD tune-in.

## 2026-10-10 (shorter single-film breaks and UHD stream diagnosis)

**Shipped.** StaticGroot 0.1.14 (15) debug APK installed on the test TV and
companion image `staticgroot-schedules:0.1.14` deployed with the existing volume.
Assembly, 119 Android tests and six server tests pass. Changes are uncommitted.

**Changed.** Single-film channels now insert at least five minutes of station
programming before a repeat, rather than an hour, followed by quarter-hour
alignment. The server validator uses the same five-minute spacing. Existing
published days are preserved: Force regenerate is required to adopt the shorter
breaks. Hourly TV-block alignment and other start rules are unchanged.

**Measured.** Active published schedules contained 271 inter-programme breaks
longer than 15 minutes; the longest was 4,440 seconds (74 minutes), caused by
single-film spacing plus alignment. A reported failing UHD fixture was MKV,
3840x2160 HEVC Main 10, Dolby Vision Profile 7/HDR10, about 89 Mbps video, stereo
24-bit FLAC. Its H.264 software transcode ran at about 0.31x real time, insufficient
for sustained playback. Jellyfin hardware acceleration is configured as none.
No GPU, encoding, media-file or bitrate settings were changed by this session.

**Next.** Regenerate and measure the new break distribution. Inspect GPU capabilities
and test hardware transcoding or a compatible lower-bitrate version for the UHD
fixture. The failing stream is diagnosed, not fixed.

## 2026-10-10 (channel return, commercial refresh and HEVC negotiation)

**Shipped.** StaticGroot 0.1.13 (14) debug APK installed on the test TV. Assembly,
lint and 119 unit tests pass. Changes remain uncommitted on `master`.

**Changed.** Settings has Return to playing channel, which restores that guide row
and current-time window without retuning, plus Previous channel, which swaps to
the last tuned channel. The remote Last Channel key uses the same action. Settings
content scrolls to keep the growing list reachable with a D-pad.

Refresh commercials only reads the Commercials library and replaces the local ad
pool for subsequent breaks. It leaves programme pools, airing times and published
manifests untouched. Synthetic transport tests verify only the commercial library
is fetched and sub-minute runtime is retained. A failed refresh retains the old pool.

**Measured.** A failing film's Jellyfin transcode log reported HEVC frame-RPS errors
and zero output frames after 5.44 seconds. The app had advertised H.264 only, forcing
server conversion of HEVC. Android now queries MediaCodecList and advertises HEVC
direct play only when the device exposes a decoder, retaining H.264 for server
transcodes. Synthetic tests verify both device profiles. This is not yet a confirmed
fix for the failing film; malformed HEVC may fail on the device too.

**Half-done.** Return actions, refreshed commercial playback and direct HEVC on the
reported film need hardware verification. The app does not repair media files or
change Jellyfin hardware acceleration. Existing stream/session handling limitations
remain.

**Next.** Retry the reported film with this build and compare first-frame/playhead
measurements with the server's transcode output. Check a commercial refresh without
changing published schedule hashes.

## 2026-10-10 (force regeneration and balanced movie repeats)

**Shipped.** StaticGroot 0.1.12 (13) debug APK installed on the test TV.
Companion image `staticgroot-schedules:0.1.12` replaced the deployed container,
retaining its persistent volume and service key. Changes are uncommitted on `master`.

**Changed.** Movie channels no longer stop scheduling after one showing per day.
Version-deduplicated pools are rotated in balanced rounds, choosing least-aired
movies and avoiding the previous movie when alternatives exist. Additional rounds
are used only to fill the day. A one-film channel inserts at least an hour of
station content before replaying it. Breaks and midpoint continuations are not
counted as new showings. There is no hard two-showing cap: larger pools need fewer
rounds and small pools need more.

Settings includes Force regenerate schedules. It reloads the library, rebuilds
three days and sends a complete replacement to the companion. The server validates
an isolated generation and changes its active pointer only after all days pass.
Old files remain for rollback/inspection. Invalid replacements leave the active
lineup unchanged. The dialog warns that regeneration changes advertised times;
other TVs need to restart. Stale clients are rejected when publishing new days
against an old generation.

**Verified.** Six server tests and 117 Android tests pass, including balanced
rotation, no consecutive repeats, version deduplication, one-film spacing,
atomic regeneration, stale-generation rejection and failed-regeneration retention.
Debug assembly and lint pass. Server redeployment and APK installation succeeded.

**Half-done.** The force-regenerate UI has not been exercised on hardware. Existing
published real schedules remain active until the user invokes regeneration. The
two-showings target is approximated by balanced least-aired rotation, not by a
global optimisation of airtime slots. Shared horizon refresh remains pending.

**Next.** Force regenerate on the TV and verify another device loads the same
generation. Measure per-channel repeat distribution and filler against real pools.

## 2026-10-10 (companion deployed alongside Jellyfin)

**Shipped.** Companion image `staticgroot-schedules:0.1.11` deployed as container
`staticgroot-schedules` on the Jellyfin Docker host, serving port 8787 with
restart-unless-stopped and persistent volume `staticgroot-schedules`. A generated
service key is kept outside the repository. The existing StaticGroot 0.1.11 (12)
TV installation is configured through its encrypted service settings.

**Verified.** Missing authentication gets HTTP 401. An authenticated synthetic
day survived a container restart and a conflicting publication unchanged.
The TV published/retrieved 393 manifests (131 channels across three days), then
the container and TV app were restarted. The SHA-256 digest of all published day
files was identical before and after, demonstrating that advertised times were
not regenerated on this round trip. Most service requests completed in 6-12 ms
on the tested LAN.

**Half-done.** A second physical TV has not been configured or tested. Initial
schedule proposals still come from Android; autonomous scanning and background
horizon extension remain pending. No changes were made to the Jellyfin container.
Repository changes remain uncommitted on `master`.

**Next.** Configure the second TV with the same service key and Jellyfin user,
verify the last tuned channel and future airtimes, then implement horizon refresh.

## 2026-10-10 (movie repeat guard and shared station service)

**Changed.** StaticGroot 0.1.11 (12) limits movies to one showing per channel per
local day, deduplicating by TMDB identity or normalized title/year. A midpoint
continuation is part of the same showing. After the pool is exhausted the channel
fills with station breaks rather than repeating a movie. Music-video rotation is
excluded from this rule. A movie may return on a later day; this is not a lifetime
ban on repeats.

`server/` contains a standalone Node companion and Dockerfile. It persists shared
channel definitions, enabled flags, station time zone, full day manifests and last
tuned channel. The first published day wins; later clients cannot overwrite its
times with a new pool or proposal. Setup flag updates use version checks. Requests
require a service key; Android stores the URL/key in encrypted local preferences.
The service never receives Jellyfin credentials. Android Settings exposes the
connection, and launch retrieves today plus two following days before showing the
guide. Shared mode refuses to invent local days outside that horizon.

**Verified.** Four Node tests cover restart persistence, conflicting device proposals,
duplicate-movie rejection versus midpoint continuation, setup version conflicts and
HTTP authentication. Android assembly, lint and 116 unit tests pass, including
movie version/wrap deduplication and published-day precedence.

**Half-done.** Changes are uncommitted on `master`. The service has not been
deployed on the Jellyfin host or verified with two TVs. Clients still compute the
initial candidate schedules; the service is a persistence/publication authority,
not an autonomous Jellyfin scanner. Background horizon extension is not implemented;
restart at least every two days. Last-channel synchronization is best effort.
Station setup uses the same Jellyfin server/user on all devices. This continues
the live station clock, not a paused private bookmark. Time gates and DST edge
cases need further integration checks before shared scheduling is production-ready.

**Next.** Deploy using `server/README.md`, configure both devices, and verify that
a published programme two hours ahead remains unchanged across restart and device
switch. Then add background horizon refresh and server-owned library scanning.

## 2026-10-10 (TV half-hours and hourly repeated-show blocks)

**Shipped.** StaticGroot 0.1.10 (11), debug APK installed on the test TV.
Assembly and 114 unit tests pass. Changes remain uncommitted on `master`.

**Changed.** Corrected the requested TV station rule: ordinary episode starts
use the next :00 or :30; the 14-minute/quarter-hour fallback is removed. When a
new run contains two or more consecutive episodes of the same series, its first
episode starts on the next hour. Subsequent episodes in that run use half-hours.
Movie starts remain quarter-hour aligned. The minimum ident and midpoint breaks
remain part of the elapsed schedule. Tests cover the first/repeated episode
distinction and ordinary series switches. Real commercial transitions still
require hardware validation.

**Next.** Verify the corrected TV grid and a commercial break with the library.

## 2026-10-10 (Commercials library and mixed station slots)

**Shipped.** StaticGroot 0.1.9 (10), debug APK installed on the test TV. Changes
remain uncommitted on `master`.

**Changed.** Libraries named Commercials, ignoring case and surrounding spaces,
are loaded separately and never feed regular channel pools. Commercial runtime is
kept in seconds so 15- and 30-second clips are not dropped by minute rounding.
Breaks fit whole clips without repetition inside a break and reserve ten seconds
for the GROOT ident. Missing clips, no fitting clips and playback failures fall
back to the animated station card. Break deadlines cap playback so ads cannot
push the next programme indefinitely. No YouTube integration or downloading.

The requested station rule replaces the previous half-hour rule: movies use the
next quarter-hour. TV uses the next hour when the wait is at most 14 minutes;
otherwise it uses the next quarter-hour. The minimum inter-programme ident is
30 seconds. Runtime includes one scheduled 60-second midpoint break for movies
and episodes longer than 30 minutes. The second segment stores the original-media
offset, so tuning after the break and resuming do not count commercial time as
programme content. This is a StaticGroot-specific extension, not Swift parity.

**Verified.** Assembly, lint and 112 unit tests pass. Synthetic checks cover the
14-minute TV threshold, quarter-hour movie starts, short clip duration, fitting
clips with an ident tail, midpoint segments and content offsets. Installation
succeeded. Actual commercial playback, failure fallback, programme pause/resume
and the full station-clock transition have not been verified with a commercial
library. HLS playback remains limited by the existing segment stalls.

**Next.** Add a Jellyfin video library named Commercials, rescan, then verify a
short ad-to-ident-to-programme transition with a direct-play fixture. Confirm
midpoint resume against a known frame/time before relying on long sessions.

## 2026-10-10 (half-hour starts and animated filler)

**Shipped.** StaticGroot 0.1.8 (9), debug APK installed on the test TV. Changes
remain uncommitted on `master`.

**Changed.** Station breaks now fill the gap to the next local half-hour boundary,
including a minimum 30-second ident. A 22-minute episode receives eight minutes of
filler. A programme ending exactly on a boundary receives a 30-minute break to
preserve the ident before the following boundary. This is an explicit StaticGroot
schedule extension, not upstream schedule parity. Actual natural ends calculate
the break deadline from the current clock before starting the next programme.
The bumper pulses the original GROOT mark and moves a blue sweep through the
background, with a minutes-and-seconds countdown. Animation stops when hidden.

The first keypress during an idle crawl is consumed to stop scrolling and select
the channel nearest the visible centre, including fractional scrolling and wrap.
The next keypress performs navigation or tuning. It no longer jumps to the tuned
channel.

**Verified.** Debug assembly, lint and 108 unit tests pass. Timing checks cover
half-hour starts, preserved episode runtime, minimum-ident boundary behaviour,
contiguous coverage and midnight clipping. Navigation checks cover fractional
centre selection and wrapping. Hardware animation and natural transitions are
not yet verified.

**Next.** Verify a full filler-to-programme transition and crawl interruption on
the remote. Commercial clips are a follow-up: prefer a user-curated local Jellyfin
library, fitting whole clips into the remaining break and filling the tail with
the station ident. YouTube links are a discovery source, not a reliable scheduled
stream; importing files should be an explicit user action for material they can use.
No commercial fetching or downloads are implemented.

## 2026-10-10 (original GROOT mark and Cyberdream palette)

**Shipped.** StaticGroot 0.1.7 (8), debug APK installed on the test TV. Changes
remain uncommitted on `master`.

**Changed.** The generic ASCII wordmark is replaced with the owner's original
nine-line shaded-block GROOT mark, referenced from `system/groot-splash.sh` in the
personal scripts repository. Header and bumper share one renderer with a monospace
font to preserve the block alignment. The script `system/sync-colors.sh` identifies
Cyberdream as the palette; app surfaces now use its charcoal `#16181a`, raised
surface `#3c4048`, blue `#5ea1ff` and cyan `#5ef1ff`. Blue marks focus and the logo;
cyan marks the time indicator, progress bar and Settings label. Channel colours
continue to distinguish individual channels.

**Verified.** A TV screenshot confirmed the block glyphs render correctly, the
blue/cyan palette appears, and both upcoming-programme lines fit below the synopsis.
Assembly, lint and 104 unit tests pass. Bumper use of the shared logo renderer is
implemented but not captured on device.

**Next.** Observe a station bumper with the new mark and continue playback repairs.

## 2026-10-10 (preview crop, idle crawl and programme information)

**Shipped.** StaticGroot 0.1.6 (7), debug APK installed on the test TV. Changes
are uncommitted on `master`.

**Changed.** Preview always center-crops to fill 4:3, including when Retro Mode
is off. Retro Mode controls CRT effects and fullscreen cropping. After 30 seconds
without input the guide crawls upward at one row per eight seconds, wrapping at
the end without retuning playback. Input restores the selected row. Fullscreen
and Settings pause the crawl.

Android channel labels replace Nostalgex with StaticGroot; shared identifiers and
filter rules are unchanged. The header has an ASCII GROOT wordmark. Programme
information now includes Jellyfin's episode/movie overview, coloured metadata,
airtimes, minutes remaining, a progress bar and the next two programmes.

**Verified.** Device screenshots confirmed cropped video filling the preview,
the ASCII logo, StaticGroot channel labels, the synopsis, and upward guide movement
while the same programme kept playing. Debug assembly and 104 unit tests pass;
lint passed before the final spacing-only adjustment. A screenshot showed up-next
clipping, so text size and synopsis length were reduced; the final adjusted panel
has not yet been captured. Existing HLS limitations still apply.

**Next.** Check final information-panel readability and crawl interruption with
the physical remote; continue playback and full-manifest persistence fixes.

## 2026-10-10 (larger preview)

**Changed.** StaticGroot 0.1.5 (6) gives the preview 60% of screen height instead
of 51.43%. At 1920x1080 its 4:3 frame grows from about 740x555 to 864x648,
about 36% more area. The information panel narrows and the guide keeps the bottom
40%. Video, bumper and CRT overlay use the same centered frame. Retro Mode still
crops to fill 4:3 by default; disabling it fits the original image.

**Shipped.** Debug APK installed on the test TV. Assembly and 104 unit tests pass.
Changes are uncommitted on `master`. Visual verification pending.

**Next.** Check the larger preview and guide readability on the TV.

## 2026-10-10 (settings return navigation)

**Changed.** StaticGroot 0.1.4 (5): Back from the guide opens Settings directly
without scrolling to channel one. Menu does the same. Both remember the highlighted
channel and restore its row and scroll position on Done or Back. Opening the nav-bar
Settings button returns to the tuned channel, so scrolling to the top no longer
loses the viewing position. If a settings change hides that channel, focus falls
back to a visible channel. Back in fullscreen still returns to the guide.

**Shipped.** Debug APK installed on the test TV. Debug assembly and 104 unit tests
pass. Changes are uncommitted on `master`; the navigation round trip still needs
hardware verification.

**Next.** Verify the settings round trip from a channel far down the guide.

## 2026-10-10 (centered retro picture and station bumper)

**Shipped.** StaticGroot 0.1.3 (4), debug APK installed on the Android 12 BRAVIA
test device. No store release. Changes remain uncommitted on `master`.

**Changed.** Retro video and its CRT overlay are centered in preview and fullscreen
instead of right-aligned. A local GROOT station card occupies a scheduled 30-second
break between programmes. It shows the channel identity, the next three programmes
and a countdown. Bumpers are explicit schedule blocks, so joining during a break
shows its remaining time rather than trying to play the next programme early.
They do not make Jellyfin playback requests and are excluded from airing history.
Natural programme end advances into the bumper before the next programme.

This is an Android-specific schedule extension requested for StaticGroot. The
underlying Swift-derived ordering remains unchanged, but airtimes now include
station breaks. The final block of a local day is clipped at midnight; a break
at that boundary can therefore be shorter than 30 seconds.

**Verified.** Debug assembly, lint and 104 unit tests pass. Three new timing checks
cover contiguous programme/break blocks, joining 12 seconds into a break with
18 seconds remaining, and midnight clipping. APK installation succeeded.

**Half-done.** The bumper has not been observed through a natural programme end
on hardware. Countdown, centered picture and preview/fullscreen rendering need
visual confirmation. Existing HLS transcode stalls remain unresolved.

**Next.** Observe one complete programme-to-bumper-to-programme transition on the
TV, then finish the HLS segment investigation.

## 2026-10-10 (StaticGroot display settings)

**Shipped.** StaticGroot 0.1.2 (3), debug APK installed on the Android 12 BRAVIA
test device. No store release. Changes are uncommitted on `master`.

**Changed.** Settings now exposes persistent Retro Mode and Show off-air channels
toggles, plus playback retry, sign-out and attribution. Retro defaults to on,
matching Swift: 4:3 crop, scanlines and a tube-edge vignette. Off fits the original
image without the overlay. VHS tracking glitches and flicker are not yet ported.
Off-air channels are hidden by default, based on the current schedule, and visibility
is refreshed every 30 seconds so time-gated channels can appear when they open.
Settings is reachable through the nav button or the remote Menu key.

**Verified.** Screenshot confirmed the settings dialog on the TV and off-air rows
hidden initially. Enabling Show off-air channels restored empty rows immediately,
and its value was confirmed in device-local preferences. It was switched back off
after the check. Debug assembly, 101 unit tests and lint pass. Existing HLS stalls
remain unresolved; this update does not establish reliable transcode playback.

**Next.** Verify Retro Mode visually with a playing programme and on a fullscreen
round trip, then continue the HLS segment investigation.

## 2026-10-10 (StaticGroot tuner repair)

**Shipped.** StaticGroot 0.1.1 (2), a debug APK installed on an Android 12 BRAVIA
test device through ADB. No store release. Changes are local on `master`, uncommitted.

**Changed.** The sidebar prototype is replaced by the tuner layout from Swift
`TunerView`: programme information at top left, a 4:3 preview at top right,
and a two-hour EPG underneath with four half-hour headings, duration-sized
programme cells, a now line and whole-row D-pad focus. Left/right scrolls time;
OK tunes a highlighted channel, then OK on the playing channel expands fullscreen.
Back returns to the same player. DM Mono is bundled with its OFL licence.

`TunerPlayback` owns one Media3 player for guide and fullscreen, invalidates stale
network results, stops hidden playback, resumes on foreground entry, handles natural
programme end, and displays tuning/buffering/error states. Session cleanup runs
separately because a measured stop request took 7.4 seconds and blocked the next tune.
The Android profile permits H.264 MKV direct play because Media3 can demux it.
All HLS requests carry authentication headers rather than relying on an inherited query.
Jellyfin identifies the client as StaticGroot. Time restrictions are evaluated when
the schedule is requested rather than frozen at the startup hour.

**Measured.** Direct-file playback rendered its first frame in 4.5 seconds and
advanced continuously through repeated five-second samples. Screenshots verified
video in fullscreen and video after Back to the guide. The guide rendered the
programme grid on device. Android unit tests (101), debug assembly and lint pass;
the shared filter and schedule smoke scripts pass (53 and 12 checks).

**Half-done.** HLS transcodes are not reliable. Master and child playlists loaded,
but one fMP4 media segment returned 24 bytes after 17.8 seconds with no first frame.
A TS-HLS zero-offset probe rendered a frame after 17.2 seconds but buffered after
about three seconds. Neither TS nor a PlaybackInfo start offset established reliable
scheduled playback; both experiments were reverted. The final path preserves the
Swift-measured client seek, and a 30-second stall displays an error and stops the
stream. Natural auto-advance is implemented but not verified through a programme end
on hardware. Styling still lacks the original description/progress details and CRT
effects. Existing manifest restart instability, enrichment and library caching remain.

**Next.** Inspect segment production/server transcoding with a synthetic fixture;
verify HLS seek, continuous play and natural end before calling playback complete.
Finish the information-panel styling and verify D-pad settings navigation.

## 2026-10-10 (fork: Android TV port)

**Shipped.** Nothing. No build went out, and `android/` does not exist yet.

**Changed.**

- `CLAUDE.md` now describes this repo as a fork being ported to Android TV, with
  the tvOS app as the spec. It adds the rules for the port: remote-only
  navigation, port behaviour not structure, keep `android/` out of the Xcode
  project, and parity checks against the Swift code on the same inputs. Signing
  secrets stay out of git.
- `README.md` and `docs/REPOSITORY-LAYOUT.md` now say this is a fork
  (`madmax3553/NostalgexAPK`, upstream `chadMueller/Nostalgex`) and list the
  Android TV surface as in development. Upstream claims (App Store, changelog,
  community links) are unchanged.
- The default branch is now `master` (was `main`). The CI trigger in
  `.github/workflows/ci.yml`, the release steps in `RELEASING.md` and the docs
  point at `master`. Vercel's production branch has to be set to `master` in the
  Vercel project, or pushes will not deploy.
- Commit `af228bc` (CLAUDE.md) was made under the author name "Batman" with the
  configured email. Git config was not changed.

- `android/` is scaffolded, uncommitted: Gradle Kotlin DSL with a version
  catalog, `versionName` 0.1.0 and `versionCode` 1, `minSdk` 26 as a
  placeholder, `targetSdk` 35. The manifest declares leanback as required and
  touchscreen as optional. `MainActivity` is a placeholder screen. Icon and
  banner are placeholder vectors. `android/.gitignore` keeps `local.properties`
  and keystores out of git.
- The package and `applicationId` were `io.github.madmax3553.nostalgex`, chosen by
  the fork owner. They were first copied from the tvOS bundle ID
  (`com.muellhaus.nostalgex`) by mistake and then changed before any release.
  The ID is permanent once an app is published, so change it now or never.
- Build verified on this machine: JDK 17, Android SDK platform 35 and
  build-tools 35.0.0 under `~/Android/Sdk`, Gradle 8.10.2 via the wrapper.
  `./gradlew -p android :app:testDebugUnitTest` and `:app:assembleDebug` both
  succeed (the test task has no tests yet). The debug APK reports
  `io.github.madmax3553.nostalgex` (since renamed to `io.github.madmax3553.staticgroot`), versionCode 1, and a leanback launcher activity.

- Port started, first slice: the `channels.json` loader
  (`channels/ChannelConfig.kt`), using kotlinx.serialization with unknown keys
  ignored. The Gradle build copies `channels.json` and `channels-memberships.json`
  from the repo root into the APK, so there is no second copy in `android/`. Seven
  unit tests read the real file: schema version 37, 131 channels, 14 bundles, 3
  exclusive rules, unique static ids, one premiere channel (403), legacy
  `channelID` normalised, and bundle references resolve. All seven pass.
- Scope for the first testable APK, agreed 2026-10-10: Jellyfin only, TMDB and
  OMDb enrichment with keys supplied at build time, no analytics (TelemetryDeck is
  not ported), Media3 for playback.

- Rule matching is ported (`channels/RuleFilter.kt`): the port of
  `AppState.filterItems`, `ExclusiveRule.matches` and `Channel.filteredPool`. It
  covers the type and source gates, genre locks, title and episode-title rules
  (word-boundary match, year suffix stripped), studio, network, keyword and
  production-company rules, OMDb gates, manifest claims and exclusivity, dates,
  ratings, duration and watch status. The clock and time zone are parameters, so
  the tests are deterministic. 41 unit tests state the rules by name, and the
  manifest loader parses the shared `channels-memberships.json`.
- Not ported yet: the music-video genre enrichment (`effectiveGenres` reads
  MusicBrainz and Deezer, which are not in v0.1), and the collection and bundle
  filters.
- Parity status, stated plainly. Three checks run here, and none needs a Mac.
  (1) The rule filter is checked against the web filter (`scripts/nostalgex-channel-filter.cjs`)
  on a shared fixture of 400 synthetic items, for all 131 channels: the kept ids match
  exactly. The fixture is written by `scripts/nostalgex-filter-fixture.cjs`.
  (2) The show interleave, premiere promotion, sequel grouping and premiere timing match
  the Swift golden vectors in `scripts/nostalgex-schedule-order-smoke.mjs`. Those vectors
  were produced by running the Swift code on Apple Swift 6.2.1.
  (3) The random generator matches an independent Python implementation.
  Not checked: the day packer and the premiere placement inside it against a Swift run,
  and the web schedule, which has diverged on purpose (premieres, sequel adjacency, the
  6-hour refresh). For the day packer, the only oracle is the Swift code itself, so a
  Swift run on the same fixture is the next step.

- Step 1 of the first testable APK: a Jellyfin client for the music-video
  channels (`jellyfin/`). It covers sign-in with the MediaBrowser header
  (`AuthenticateByName`), libraries from `/UserViews`, paged `/Items` reads,
  and the item mapping. Runtime uses 10,000,000 ticks a second. Music-video
  libraries tag items "Music Video", and short music-genre clips in a movie library
  count as music videos too. Playback resolves through PlaybackInfo, which uses
  the server transcode URL first, then a static direct-play stream. This follows
  tvOS `JellyfinPlaybackResolver`. 19 unit tests use synthetic JSON and a fake
  transport. No server was contacted.
- `MusicChannels` builds the pools for the `musicVideo` channels with the same
  `RuleFilter` as the rest. The channel list and player are plain views, not the
  Leanback UI. The player is Media3 `ExoPlayer`.
- Known gaps in step 1, all deliberate: the sign-in token lives in memory only, so
  the user signs in on every launch; the device profile is H.264 only (no HEVC, no
  8-bit cap, placeholder bitrate); cleartext HTTP is allowed for LAN servers, as on
  tvOS; playback is not scheduled, so each channel plays its first item and the
  screen says so; the playback session is not reported or stopped on the server.
- Not verified: the player, the sign-in screen and the stream path have not run on
  a device or emulator, and nothing has been checked against a real Jellyfin
  server. The 19 client tests show the request and response handling only.

- `README.md` no longer carries the original author's donation link. The fork
  credits the upstream project under the MIT license and says it is not endorsed
  by the author. The app name is still open: upstream asks forks to ship under
  their own name, and the APK is still called Nostalgex.

- The Android app is named StaticGroot. The launcher label and the sign-in screen
  credit the upstream project under MIT, with a note that the fork is not endorsed by
  the author. The package ID is now `io.github.madmax3553.staticgroot`, to match
  the name. The old ID `io.github.madmax3553.nostalgex` was changed before any
  build went out. The name carries a trademark risk (Groot is a Marvel name).
  The fork owner accepted that risk.

- Schedule ported (`schedule/`): the seeded RNG, Swift's shuffle, the show
  interleave, premiere placement, sequel-part grouping, the day packer with its
  two seam rules, and the now/up-next lookup. 14 schedule tests and 4 golden tests
  for the random generator. The golden values come from an independent Python
  implementation of the same algorithm, not from a Swift run. This machine cannot
  run Swift, so the exact lineup is NOT yet checked against tvOS. That check is
  the Swift tests run on a Mac, or a fixture run against the Swift output.
- Not ported yet, so no lineup is shown on device: the yesterday-history store
  (`AiredHistory` is an interface with an empty default, so the seam rules see no
  history on a fresh install), the guide skin, the live preview, and Jellyfin TV
  episodes.

- The guide is on device, built and installed (not yet checked by the user).
  Pools cover all 131 channels from the Jellyfin movies and TV episodes, through the
  shared `RuleFilter` and `filteredPool`. TV episodes take the series' title,
  genres, TMDB id and year, as in tvOS `fetchEpisodes`. The schedule keeps a per-day
  history in app storage (`FileDayHistory`), so the cross-day seam rule works from
  the second day on. The guide is a channel list with a muted live preview. It seeks
  to what is on now, and OK opens the full-screen player at the same point. The skin
  uses the VT323 font from the repo, with each channel's colour. The CRT overlay and
  the static transition are not ported.
- 95 unit tests pass. Not verified: the lineup against tvOS, the preview and player
  on the TV, the seek accuracy, and the episode load time on a large library.

- Fixed on device: "Open guide" opened the Jellyfin app. `GuideActivity` was not
  declared in the manifest, so starting it crashed StaticGroot, and the launcher
  showed the previous app. It is declared now.
- The Jellyfin session (server address, access token, user id) is saved in
  EncryptedSharedPreferences, with the key in the Android Keystore
  (`SessionStore`). It is not in the repo or in plain storage. Launch resumes into the
  guide with no sign-in. A 401 or 403 from the server clears the token and shows
  sign-in again, with the address filled in. The first sign-in after this build still
  needs a password or Quick Connect, because no session was saved before.
- Not yet: a sign-out control, and the library reloads on each launch (no cache).

- The "freeze" was real: the channel build ran at 100% CPU for several minutes on the TV
  with no progress shown. Cause: each channel re-checked every item against the
  exclusive rules and the membership manifest, so the work was channels times items.
  The item-level facts are now computed once (`RuleFilter.prepare`), and channels reuse
  them. On the TV the build of 2,655 items into 131 channels takes 2.7 s (parse 0.2 s),
  measured from the device log. The golden cross-check still passes after the change.
- The player and guide now log their state and errors under the StaticGroot tag
  (`PlaybackLog.kt`). Stream URLs are not logged.
- Known: the preview panel stayed black in the one check made, and the playback log
  showed buffering with no error. Not yet confirmed whether video renders.
- Known: when the saved server does not answer, resume shows the raw timeout and offers
  no retry. The fix is a retry button and a clearer message.

**Half-done.** The daily schedule (`DailyManifestScheduler`, `SchedulePoolOrdering`),
enrichment (TMDB and OMDb), the guide and the Leanback UI are not ported. No APK has
been installed on a device or emulator. The Android app is not released.

**Next.** A YouTube Music spike on a real device (see the todo list in the session).
Then the schedule port, which turns a pool into what is on now.

**Not for the port.** The Swift test suite and some Swift comments name real
people and places: a library size, an author name, a home LAN address and a
Plex machine identifier (for example `NostalgexTests/DuplicateServerTests.swift`
and `Nostalgex/Services/PlaybackStarvation.swift:5`). The Android port carries none
of them. The Swift tree keeps them until the Swift app is deprecated, which the
fork owner plans to do after the Android port is done. No Swift change is planned
before then.

**Next.** Start the feature port from the Swift
channel and schedule logic, with a parity check against the same `channels.json`.

## 2026-10-09 (app)

**Shipped.** Nothing to Apple. 1.0.24 (45) is still in TestFlight beta review.
`main` now carries 1.0.25 (46), not archived or uploaded.

**Changed.**

- Jellyfin and Emby connect forms have a FIND SERVERS ON MY NETWORK button
  (`LANServerDiscovery.swift`). UDP 7359, `who is JellyfinServer?` and
  `who is EmbyServer?`, three second listen, deduped by server id, kind taken
  from which probe the server answered. Measured before writing it: the Emby
  under test answered the subnet directed broadcast and ignored
  255.255.255.255, so the app sends to both, computing the directed broadcast
  from every live IPv4 interface. Network.framework could not receive the reply
  (a connected UDP socket drops datagrams from the server's unicast address),
  so it is a BSD socket with SO_BROADCAST. No system prompt seen in the tvOS 26
  simulator; `NSLocalNetworkUsageDescription` now names all three servers in
  case hardware asks. Jellyfin side verified only as far as the parser, no
  Jellyfin server was on the test network. Plex skipped on purpose: that form
  has no address field.
- The Plex to Emby switch bug from the 2026-10-08 entry is fixed, and the cause
  was not either suspect named there. It was a race: a stale snapshot starts a
  background Plex refresh at launch, which on a large library runs for minutes;
  the user disconnects and signs into Emby meanwhile; Emby's scan finishes
  first; then the Plex scan returns and, with nothing checking that its sign-in
  still exists, rebuilt every pool from Plex items and saved the snapshot under
  the Emby identity. That poisoned snapshot is why it survived relaunch. The
  harness (`BackendSwitchScheduleTests.swift`) showed the clean switch passing
  on the old code and the race failing with "CH 7 VHS VAULT schedule would send
  18 Plex key(s) to Emby". Fix: a session generation bumped on Disconnect or a
  server change, checked before every commit in `loadLibrary` and used to abort
  the remaining requests; items from servers not signed in are dropped at load;
  a snapshot holding such items is refused on launch and rescanned; playback
  skips a foreign item with the failure card instead of sending the server a
  request it will answer 500. Disconnect now also clears today's manifests and
  the collection scan. Behaviour change: disconnect and re-sign-in to the same
  server reshuffles today's guide.
- Settings: Connection moved above Display and took in the report-playback
  switch (it had its own section). The two subtitle switches became one
  SUBTITLES row cycling Off, Foreign audio, Always: the fullscreen switch only
  ever overrode the foreign-audio one, so they were one three-way setting.
  Subtitle language only shows when subtitles are not Off. The Buy Me a Coffee
  and update-email QR codes are gone from Settings, with their analytics event.
  The in-player panel still has separate CC and auto buttons.
- `NostalgexTests`: 436 passed, 0 failed, 2 skipped on the merged tree
  (417 before, 9 discovery, 9 backend switch, 1 moved).

**Verified on hardware** (Apple TV 4K, first generation): RESCAN LIBRARY on a
Plex library of about 11,600 items, Disconnect mid scan, then Emby found via FIND
SERVERS. The log read "sign-in changed while the scan was running, discarding its
results", the Emby library loaded, and five programmes played with no HTTP 500s.
The automatic background refresh that started the original report was not
reproduced (the snapshot was only hours old) but runs through the same check. No
local network prompt appeared; a clean install is still the true first press.
A 4K HEVC title with EAC3 audio that stalled in the simulator played cleanly.

**Next.** 46 to TestFlight. App Store
listing for the next submission: "131 themed channels across 14 bundles" and
new screenshots. Discussion #2 (Fire TV fork): ordering and DayPacker ports match
the JS reference byte for byte; the channel filter does not (22 channels
diverge), asked the contributor for filter golden vectors in `scripts/` before
deciding where it lives. Discussions #8 (Roku) and #14 (languages) are
unanswered.

## 2026-10-09

**Shipped.** nostalgex.app: the four SEO blog drafts and table styling (#15),
then site wide SEO and speed work (#16). The buddy cop post went live a day
early; the other drafts each moved up a day (Oct 12, 14, 16, 19, 21) and still
need their `draft: true` removed on the day. No app change.

**Changed.**

- Hero text no longer waits for JavaScript. `.hero-copy` (and `.hero-shot`) on
  `/`, `/plex`, `/jellyfin` and `/emby` lost the `reveal` class, which kept them
  at opacity 0 until `app.js` ran. That was most of the mobile LCP.
- Hero media is lighter and off the critical path. New poster
  `guide-hero-1280.webp` (54 KB, was 511 KB) is preloaded; the video is
  `preload="none"` and `app.js` starts it after `load`, never under reduced
  motion or Save Data. New encodes: 480p mp4 412 KB (phones), 1600 webm 1.46 MB
  and 1280 mp4 1.37 MB (desktop). Old files stay for one release so cached
  pages don't 404. Screenshots below the hero get 1280 and 1920 `srcset` sizes.
- Lighthouse mobile, local static build, same machine before and after: `/`
  went 71 to 96 (LCP 6.3 s to 2.4 s, 1,068 to 745 KiB), `/jellyfin` 74 to 94
  (LCP 8.1 s to 2.3 s). Not yet measured on the Vercel preview.
- `/plex`, `/jellyfin` and `/emby` were near copies, and two of them sat outside
  Google's index. Each now has its own H1, direct answer, setup steps, tips and
  gotchas, and six server specific FAQs.
- Every page has new titles and descriptions, a 1200x630 card image
  (`og-image-1200.png`, letterboxed because a center crop clipped the logo), a
  48px favicon, an apple-touch-icon, and one JSON-LD `@graph` linking the app,
  Chad and Muell Haus by `@id`. Visible FAQs and FAQPage text are generated from
  the same strings.
- Blog: titles end in " | Nostalgex", Article author is the Person `#chad`,
  posts get a BreadcrumbList, and new optional front matter `cover_alt`. FAQPage
  and table wrapping from #15 are kept, folded into the single graph.
- Sitemap entries carry `lastmod` only (Google ignores changefreq and priority).
  Added `public/llms.txt`.
- Support page CORS configs said `https://nostalgex.app`, but the tuner runs on
  `https://www.nostalgex.app`, so the documented config could not work. Fixed.
- Removed the Data Haus analytics script from every page, the blog template and
  the CSP. statsngraphs stays. The privacy page, README and the support FAQ
  now describe one analytics script; the support FAQ no longer says there are
  no analytics on the tuner pages, because the connect page loads one.
- "Over a hundred" is now 131 everywhere it appeared.
- `Nostalgex/PRIVACY_POLICY.md` matches the privacy page: statsngraphs only,
  effective October 9. `docs/FAQ.md` named 1.0.22 for the Tailscale/VPN http fix,
  which shipped in 1.0.23 (support page was already right).

**Half-done.** Rich Results Test and a Lighthouse run against production still
need doing.

**Next.** Merge #15 first, then this. After deploy, resubmit the sitemap and
request indexing for /plex, /jellyfin, /support and the blog post.

## 2026-10-08

**Shipped.** 1.0.23 reached the App Store (build 44). It carries the Jellyfin
channel-change fix, the Plex playback fixes, the SCREAM and TIS THE SEASON
packages, eleven new channels and the Y2K renames. The web tuner on
nostalgex.app shipped separately the same day.

**Changed.**

- Merged #5 and #7 from @Gorbataras, who found the tune-in bug independently and
  traced it further than we had, through Jellyfin's `DynamicHlsController` across
  10.10.7, 10.11.0, 10.11.11 and 12.1. #5 routes **Emby** through the same
  client-side seek Jellyfin already used, and deletes `addingStartTime`, which no
  longer had a caller. #7 stops Jellyfin and Emby direct-playing the two things
  AVPlayer will not render: HEVC in MP4 tagged `hev1` (it renders only
  `hvc1`/`dvh1`) and 10-bit H.264. Both guards already existed on the Plex path
  and had never been ported.
- Closed #3. The symptom was the tune-in bug, fixed in 1.0.23 and verified by
  @Gorbataras on an Apple TV 4K against Jellyfin 12.1.
- **Plex adaptive mode is no longer requested.** With `autoAdjustQuality=1`, any
  film where the server re-encodes both video and audio came back as 3-second
  segments: the first eight arrived at full speed, then one every 6.1 seconds for
  the rest of the film. tvOS 26 abandons a segment that slow (CoreMedia -15628)
  and the picture died at 24 seconds, at 4K and at the 1080p retry alike. With it
  off, the same request returns 1-second segments and keeps pace. Audio-copied
  streams were never affected, which is why some channels played and others did
  not.
- **Dolby Vision profile 5 plays on Plex.** Plex refuses to re-encode DV5
  (decision 2003), but it will remux it for a client it resolves as Generic,
  which is what Plex's own Apple TV app gets: `container=mp4, video=copy hevc
  DOVI 5`. On a DoVi refusal the app now re-asks once that way. Measured on
  device: picture in 1.8s, 61.6s of playhead across a 60s hold, zero stalls, zero
  dropped frames.
- **A starving stream steps down instead of freezing.** When a server cannot keep
  up, the same film is re-requested from the current position capped at 1080p,
  and only a starving capped stream advances. Previously it froze or jumped to
  the next programme.
- **An offset HLS transcode reports the remainder as its duration**, not the full
  runtime. The end-of-item fallback was adding the offset back, so `remaining`
  went negative on every tuned-in transcode and the first rate-0 moment skipped
  to the next film.
- **Web tuner: a failed stream retried forever.** The hls.js error handler
  guarded its one recovery with a flag written onto the error payload, and hls.js
  builds a fresh payload per event, so the guard never held. Measured against a
  playlist of 404s: **14,612 requests in 8 seconds**, roughly 1,800/s aimed at
  the user's own server; the same handler with the flag held outside the event
  made 14. A real session was caught making 2,239 requests for one dead segment
  while an unrelated channel played normally.
- **Web tuner: each playback now mints its own Plex transcode session** and
  releases it on the way out. Without one, Plex keyed the transcode off the
  client id alone, so every channel change quietly repurposed the previous
  session, which is what left the old loader requesting segments that 404.
- **Web tuner: a manifest that will not load now skips.** `start.m3u8` answering
  400 produces exactly one fatal `manifestLoadError` and nothing after it, so
  `startLoad()` had nothing to retry and the channel sat on black indefinitely.
  It now says so and moves to the next programme, stopping after three failures
  in a row because that points at the server rather than the file.
- **Music video genres come from Deezer** when the server reports none. On a test
  library of 1,131 music videos only 148 carried a genre, and MusicBrainz tags
  filled almost none of the rest, which is why CALIENTE and HEADBANGERS sat
  empty. Deezer resolved 47 of a random 60, and a filename cleaner (underscores,
  scene tags, `ft.`/`feat.`) recovered 10 of the 13 misses.
- **Music video years** are read from the filename first, then MusicBrainz, then
  the server only when plausible. Servers commonly report 1970 or the date the
  file was added, and Deezer's year is the album's, wrong on 15 of 15 sampled, so
  the decade channels were sorting by the wrong year.

**Changed, evening session (real hardware).** Build 1.0.24 (45) ran on an Apple TV HD
(tvOS 26.6) and an Apple TV 4K simulator against an Emby 4.10.1.0 server. Everything below
was measured there, not inferred.

- **Issue #6 confirmed fixed on hardware, with a controlled before/after.** The shipped
  1.0.23 (44) from TestFlight on the same device: `HTTP 404. The file '/UserViews' could
  not be found.` Build 45 ten minutes later: `[Emby] LIBRARY: 1541 items`, 69 channels,
  playback at a 57-minute mid-schedule join. Gorbataras's #5 Emby seek is confirmed by the
  same run.
- **The guide wrap works on tvOS 26.6**, the reporter's exact version, which the 26.1
  simulator could never reproduce. Down wraps, Up reaches SETTINGS.
- **The failure card now says why, accurately.** Three classifier corrections from
  watching it misfire: the probe walks master to variant to first segment, because the
  master answers 200 while every segment beneath it is refused; CoreMedia -12889 (no
  response in 3s) and -15628 (segment abandoned) classify as the server being too slow,
  not the device failing to decode; and a decoded frame vetoes "Apple TV couldn't play
  this file" outright. Verified verdicts on hardware: `Emby HTTP 500, refused before
  video` and `Emby below real time on the capped stream`.
- **Audio track selection.** Jellyfin and Emby hand back the file's default track, and
  Joe Dirt's default is Spanish 2ch with English 5.1 unflagged, so it played in Spanish
  on a channel with no picker. The resolver now reads MediaStreams from PlaybackInfo,
  prefers a track in the viewer's language with the most channels, then the default,
  then the first, and pins `AudioStreamIndex` on the transcode URL. Verified against the
  server: Emby's own URL pinned index 1 (spa); ours pins 2 (eng); Emby accepts it. 7 tests.
- **The starvation ladder's Emby rung fired for real** (The Wedding Singer, 13 Going on
  30): starve, cap to 1080p, `TRANSCODE READY`. It recovers what it can.

**Found, not fixed.**

- **Switching backend keeps the old day schedule.** A device signed into Plex, then
  Disconnected and connected to Emby for the first time, kept playing the Plex running
  order: Plex rating keys sent to Emby resolve to Person records and 500, every channel,
  looping between two titles indefinitely. A fresh install is flawless, so it is the
  transition that leaks. `DailyManifestStore.clearAll` on Disconnect uses the fingerprint
  current at that moment, and `apiForServer` never checks an item's `serverID` belongs to
  the connected backend. Workaround: delete and reinstall. Worth fixing before a wide push.
- **Two test rigs invent failures.** The tvOS simulator has no EAC3/AC3 decoder, so ~25%
  of a typical library stalls 3s in and looks like starvation; the Apple TV HD has no HEVC
  decoder, so every HEVC title forces a server re-encode that held 1.07x here against
  CoreMedia's 3s first-segment patience. The same file for an HEVC-capable client is a
  17.3x remux. Playback claims need the 4K unit.
- **On this Emby, 10-bit sources cannot transcode at all.** `VideoFilters.format..ctor()`
  throws "An item with the same key has already been added. Key: threads" before ffmpeg
  starts; 190 of 1541 movies affected. Ruled out by measurement with the server restored
  afterwards: codec, resolution, bitrate, subtitles, audio stream, thread count, hardware
  acceleration. A server bug, reported by the card as a refusal, nothing for the app to do.

**Half-done.**

- `fix/emby-userviews-404` — **Emby library scans fail immediately.**
  `loadSections` asked for `/UserViews?userId=`, which is Jellyfin's spelling.
  Measured against Emby Server 4.10.1.0: `/UserViews` answers 404,
  `/Users/{id}/Views` answers 200. It is the first request after sign-in, so it
  fails whatever the libraries are named and however large they are. Fixed with
  three tests on the branch, not yet merged. This is issue #6, and it is present
  in the released 1.0.23. **Now verified end to end against an Emby server**
  (see below).
- `feat/playback-failure-card` — **a programme that will not play now says why,
  instead of being swapped for the next one in silence.** Only three of about eight
  failure paths showed any UI at all; the startup watchdog, the starvation verdict and an
  AVPlayer item reaching `.failed` all advanced with nothing on screen, which reads as a
  broken app and hides whether the person should look at their server or at the file.
  `PlaybackState.error` now carries a `PlaybackFailure` rather than a sentence, and every
  failure path funnels through one place that records the verdict, emits the matching
  analytics code and shows a card for five seconds before advancing. Amber when the server
  is the thing to go and look at, red when the file or the device is.
  Getting the reason right needed a new source of truth: tvOS has no `httpStatusCode` on
  `AVPlayerItemErrorLogEvent`, so the status of a refused segment cannot be read out of the
  player, and that status is the difference between "your server refused this" and "this
  file will not decode". `StreamFailureProbe` asks the server instead, with one ranged GET
  on the URL that just failed. 14 tests, classification being a pure function over what was
  observed. Not merged.
- The startup watchdog's capped retry had the same missing client-side seek the starvation
  ladder got, so a watchdog retry on Jellyfin or Emby would have restarted the film from
  frame one. Fixed on the same branch as the ladder.
- `fix/starvation-ladder-all-backends` — **the starvation ladder had no rung on
  Jellyfin or Emby, so one stall skipped the programme.** `handleStarvation` steps
  a starving stream down to 1080p and only advances once there is nothing smaller
  to ask for, but `cappedTranscodeURL` was implemented on Plex alone and the
  `MediaBackend` default returns nil. Measured on Emby Server 4.10.1.0: each
  6-second segment took 3.4s to produce before its first byte, at 128 Mbps once
  flowing, so the server and not the network was the limit; a film jumped out
  mid-scene to the next scheduled title at its first frame. Both backends now
  build the rung at 1920 wide and 8 Mbps under their own `PlaySessionId`, with no
  start time on the URL, because the segment handler refuses one. A new
  `cappedStreamStartsAtOffset` says which backends seek client-side. 4 tests.
  Not merged.
- `fix/backend-error-copy` — error messages name the server the user actually
  connected to. `PlexAPIService.APIError` is the shared error type for all three
  backends, but every message hung off it was written for Plex, so an Emby user
  whose scan 404s was told "Plex (or your network path) returned HTTP 404" and
  sent to check Plex's Remote Access setting. Plex keeps its own copy, including
  the `/library` and `/identity` paths only Plex serves. One test asserts no
  Jellyfin or Emby message contains the word "Plex", across every error case.
  Not merged.
- `fix/guide-wrap-scrolling` — the guide's vertical wrap now runs off a focusable
  sentinel row just outside the last channel rather than a 150ms staleness timer,
  so "ran off the end" is proven by the focus engine instead of inferred from a
  clock. Up out of channel one deliberately still leaves for SETTINGS and does
  not wrap. 9 UI tests. Not merged. The report that prompted it came from tvOS
  26.6 and never reproduced on the 26.1 simulator at any list length or cadence,
  so treat this as hardening fragile timing rather than a confirmed fix.
- The web tuner still appends `StartTimeTicks` to its Jellyfin fallback URL in
  `getJellyfinTranscodeUrl` (`plex-tuner.html`). Same defect #5 fixed on tvOS.
  hls.js already seeks via `startPosition`, so the parameter only breaks segment
  requests. Spotted by @Gorbataras. In progress.
- **Emby, measured.** The Emby halves of #5 and #7 were reasoned from Jellyfin's
  HLS controller being forked from Emby's, and from Emby's own web client seeking
  client-side. Both now ran against Emby Server 4.10.1.0: sign-in, library scan,
  guide build, and a mid-programme join that started Heat at 47 minutes into a
  2:50:00 film and advanced 25s of playhead across 25s of wall clock with no
  stall. The scan only ever touches `/Users/{id}/Views` and `/Items`, and series
  and episodes go through that same `/Items` call with a different
  `IncludeItemTypes`, so a movies-only test library still exercises every HTTP
  path the scan makes. What remains untested on Emby is episode *parsing*, not
  whether an endpoint answers.

**Next.**

- Merge the three branches above.
- Port the web tuner's `StartTimeTicks` removal.
- Exercise an Emby library that contains TV series, to cover episode parsing and
  the per-show grouping the movies-only run could not reach.
