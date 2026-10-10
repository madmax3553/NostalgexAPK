# Nostalgex repository layout

This repo is intentionally **one project, four surfaces**. It is a fork: upstream is `chadMueller/Nostalgex`, and this fork is `madmax3553/NostalgexAPK`. Everything that ships should live **in this repo**. Do not treat another monorepo as the source of truth for Nostalgex.

## Surfaces

| Surface | Role | Status | Typical entry |
|--------|------|--------|----------------|
| **Public site** | Marketing + Plex connect; routes users into the tuner | Live (upstream) | `/` → `index.html` |
| **Web app (tuner)** | Full in-browser guide + playback | Live (upstream) | `plex-tuner.html` |
| **tvOS app** | Native Apple TV experience. Reference for the port. | Shipping upstream. Frozen here except fixes that go to both platforms. | Xcode: `Nostalgex/Nostalgex.xcodeproj` |
| **Android TV app** | Native Android TV (leanback) port of the tvOS app | In development in this fork. Not released. | Gradle: `android/` |

**User flow:** landing → connect Plex → **web tuner** on the same origin. The **tvOS app** and the **Android TV app** share `channels.json` and filter semantics with the web tuner. Release tvOS via Xcode / App Store Connect, not Vercel. Android has no release channel yet.

## Canonical paths (repo root)

| Path | Purpose |
|------|---------|
| `android/` | Android TV app (Gradle). Kept out of the Xcode project and the reverse. |

## Canonical paths (repo root)

| Path | Purpose |
|------|---------|
| `index.html` | Public landing (SEO, newsletter, footer, Plex link flow) |
| `plex-tuner.html` | Web app (guide, player, settings) |
| `channels.json` | Channel + bundle definitions (shared contract) |
| `public/channels.json` | Mirror/symlink for static hosting — keep in sync with root or generate in build |

**tvOS builds:** `Nostalgex.xcodeproj` runs a **Sync channel config** script that copies `channels.json` and `channels-memberships.json` from the repo root into every `.app` (simulator and Apple TV). After rule changes, build with destination **My Apple TV** (not only Simulator), then **Rescan library** on device.

To confirm the build picked up the current config, filter the Xcode console for `[Plex90] CONFIG:`. Against `channels.json` v24 the line reads:

```
[Plex90] CONFIG: bundled - v24, 121 ch, CH132=NETFLIX, CH65 title rules=31
```

(example from an older config; the channel count in a current build is 131, see below)

A stale build shows an older version number or a different channel name. Regenerate the expected values from the config rather than trusting this doc: the diagnostic is built in `AppState+Channels.swift` (`configDiagnostic`), and its two channel labels are **mislabeled** for historical reasons. `CH132=` actually prints the name of channel **id 122**, and `CH65 title rules=` counts `titleContains` on channel **id 65**. Channel `id` and channel `number` are **not the same value** for 81 of the 131 channels, so read these as ids: id 122 is CH121 NETFLIX, and id 65 is CH77 SUPERHERO MOVIES. `channels-memberships.json` also keys off `id`, never `number`.
| `CHANNELS.md` | Human-readable channel reference (keep near `channels.json`) |
| `api/` | Vercel serverless (e.g. newsletter `subscribe`) |
| `vercel.json` | Headers / routing; **web only** |
| `Nostalgex/` | Xcode project + Swift sources + assets |

The nested folder `Nostalgex/Nostalgex/` (app target inside the project) is normal for Xcode; **web files still live at the repo root.**

## Shared logic

- **Filtering / schedules:** Keep `plex-tuner.html` (JS), `android/` (Kotlin) and `Nostalgex/.../AppState.swift` (Swift) aligned. The Swift code is the reference for the Android port. Shared data lives in `channels.json`; duplicated rule semantics should be updated together, and checked with `scripts/nostalgex-filter-smoke.mjs` and `scripts/nostalgex-schedule-order-smoke.mjs`.
- **Secrets:** TMDB / Supabase / OMDb keys stay in ignored files (see repo `.gitignore`) and Vercel env vars — never commit real credentials.

## Deployment

| Surface | Builds where |
|--------|----------------|
| Web | **Vercel** — GitHub integration on this repo, `master` → production |
| tvOS | **Xcode** — Archive → App Store Connect |
| Android TV | **Gradle** — not released yet. Signing keys stay out of the repo (`local.properties` or environment variables). |

Vercel must not attempt to compile the Xcode project or the Gradle project.

## Working alongside other repos (e.g. Muell Haus assistant workspace)

If you keep a **separate checkout** of this repo inside a larger folder (for local convenience):

- Use a **normal clone** of `https://github.com/chadMueller/Nostalgex.git` next to other work, **or** a **submodule** if a monorepo truly needs a pointer.
- **Do not** `git add` the Nostalgex tree into a different repository’s history — it breaks Vercel’s Git link and duplicates source.

The Muell Haus `vibing` repo intentionally **gitignores** `My Assistant/projects/plex-90/` so a local Nostalgex folder does not get committed there by mistake.

## Restoring a full tree

If you only see part of the repo (e.g. Xcode project only), run `git pull origin master` from a clean clone of **this** repository.
