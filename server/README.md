# StaticGroot shared schedule service

A standalone companion for Jellyfin, not a Jellyfin plugin. Jellyfin still serves
the media. This service stores channel setup, full published day manifests and
the last tuned channel for each Jellyfin server/user combination.

## Start with Docker

Requires Docker and persistent storage. From the repository root:

```sh
docker build -t staticgroot-schedules ./server
docker volume create staticgroot-schedules
```

Generate a service key and keep it in your password manager:

```sh
openssl rand -hex 32
```

Run the service with that value in your shell environment:

```sh
export STATICGROOT_TOKEN='your-generated-key'
docker run -d --name staticgroot-schedules --restart unless-stopped \
  -p 8787:8787 \
  -e STATICGROOT_TOKEN \
  -v staticgroot-schedules:/data \
  staticgroot-schedules
```

For a direct Node installation, use Node 22+, set `STATICGROOT_TOKEN` and
`STATICGROOT_DATA` to a private persistent directory, and run:

```sh
node server/schedule-service.cjs
```

Use a trusted LAN address or an HTTPS reverse proxy; the service key authenticates
all station access. Keep keys and data directories out of git.

## Connect the TVs

On each TV, Settings > Shared schedule service:

1. Enter the service URL, for example `http://your-server:8787`.
2. Enter the same service key.
3. Save and restart StaticGroot.
4. Sign into the same Jellyfin server and user on each TV.

The first TV publishes the channel definitions, station time zone and full days.
Subsequent TVs use that setup and the same published programmes. A changed device
library or app restart cannot overwrite an already-published day. Tune a channel
on one TV and the next launch on another selects it, joining the live programme
at the station-clock position. This is live-channel continuity, not resuming a
paused movie from a private bookmark.

Published blocks include breaks and content offsets. If an item disappears from
the library or the user loses access, loading reports it rather than silently
substituting a different advertised programme.

## Force regeneration

Settings > Force regenerate schedules rebuilds today and the following two days,
including a library reload. The app sends the complete replacement to the service,
which validates it in a new generation directory before atomically changing the
active generation. Previous generation files are retained. A failed replacement
does not change the active station. Other TVs must restart after regeneration.
Regeneration is the explicit exception to the published-time guarantee: the
confirmation dialog warns that advertised times will change.

Movie rotation uses balanced rounds to fill the day, introducing additional
showings only when required. Movies with alternatives do not repeat consecutively.
A one-film channel inserts at least five minutes of station programming before replay,
then aligns the next showing to the quarter-hour.

## Current boundaries

- The service is a persistent publication authority. An Android client currently
  computes candidate schedules; the service validates and locks the first one.
  It does not yet scan Jellyfin or build schedules autonomously without a client.
- The app fetches today and two following days on launch. Restart at least every
  two days to extend the horizon. Beyond it shared mode shows no schedule rather
  than silently making a different local one. Background horizon refresh is pending.
- Last-channel updates are best effort. Failed updates are not queued offline.
- Station definitions and enabled flags are shared. Display preferences (CRT,
  off-air visibility) remain local to each TV.
- Administrative channel-enable changes are available through the versioned setup
  endpoint; a TV channel-management screen has not been added yet.
- Run one service process per data volume. The file-store publication logic is
  synchronous within that process, not a distributed multi-instance database.
- No service has been deployed onto the user's Jellyfin host by this change.

## API

## Playback profiles

### Channel overlap audit

`server/channel-audit.cjs` supports the original distinct-item comparison and a
60% two-hour series-airtime comparison. See `docs/EPISODE-PROGRAMMING.md` for the
metric and command. Android uploads the catalog through the authenticated
`POST /v1/stations/{station}/catalog` endpoint. Catalog refresh does not replace
published programme times. No automatic channel merging is implemented.

The service stores observations independently of schedule generations. Each record
is keyed by item ID, source freshness fingerprint (Jellyfin ETag/source/size/runtime)
and Android decoder capability class. A changed source or different decoder class
does not inherit a previous setting. Up to 32 observations are retained per record.

Android reports startup time, playhead progress, elapsed wall time and attempted
bitrate/height. A setting becomes preferred only after 30 seconds of near-real-time
playback. Failure invalidates matching preferred settings. PlaybackInfo is still
requested to validate source freshness and get a current session URL; saved URLs
and access tokens are never cached in these records.

FFmpeg encoder speed is a separate nullable field. It is **not** inferred from
playhead speed. Automatic quality stepping and encoder-progress collection remain
pending. Only videos actually attempted accumulate observations; there is no full
library background probe yet. Sources without ETag are not treated as reusable.

`GET /v1/stations/{station}/playback?itemId=...&source=...&device=...` retrieves a
profile. `POST` to the same path records an observation. Both require authentication.

All requests require `Authorization: Bearer <service-key>`.

| Endpoint | Purpose |
|---|---|
| `POST /v1/stations/{station}/setup` | Create setup if absent; otherwise read saved setup |
| `GET /v1/stations/{station}/setup` | Read definitions, enabled flags, version and time zone |
| `PUT /v1/stations/{station}/setup` | Update enabled flags using current version; stale version gets 409 |
| `POST /v1/stations/{station}/days/{channel}/{YYYY-MM-DD}` | Publish complete blocks if absent; otherwise return published blocks |
| `GET /v1/stations/{station}/days/{channel}/{YYYY-MM-DD}` | Read a published day |
| `POST /v1/stations/{station}/selection` | Store last channel |
| `GET /v1/stations/{station}/selection` | Read last channel |
| `POST /v1/stations/{station}/regenerate` | Atomically publish a complete replacement generation |

Station IDs are SHA-256 of the Jellyfin server ID and user ID. They are not secrets;
the service key controls access. The service never receives the Jellyfin login token.

Tests use synthetic data:

```sh
node --test server/schedule-service.test.cjs
```
