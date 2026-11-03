# Nostalgex Channel Guide

<!-- GENERATED FILE — do not edit by hand. Run `npm run channels:doc` after any
     channels.json change. Source: scripts/render-channels-doc.mjs -->

Quick reference for every channel and bundle. Source of truth: `channels.json`.

## Read this before editing a channel

**`id` and `number` are NOT the same thing.** A channel's `number` is what
viewers tune to; its `id` is the stable key everything else references. They
drifted apart as channels were renumbered, and today they differ for many
channels — for example `id` 40 is **CH85 TRUE CRIME**, and `id` 211 is
**CH140 CRITERION**. Get it wrong and you edit a different channel than the one
you meant.

Which key does what:

| Uses `id` | Uses `number` |
|---|---|
| `channels-memberships.json` (the TMDB membership manifest) | The on-screen channel number and guide ordering |
| `exclusiveRules.channelID` / `channelIDs` | Nothing else |
| `bundles[].channelIDs` | |
| Daily EPG manifest cache keys | |

Every table below shows both.

**Pool filter logic must stay aligned across three implementations.**
`scripts/nostalgex-channel-filter.cjs` (web tuner + devtools) mirrors
`Nostalgex/Nostalgex/App/AppState+Channels.swift` → `filterItems` (tvOS).
They do not share code, so a rule change has to land in both or the platforms
drift. TMDB membership manifest: `channels-memberships.json`.

**Schedule ordering must stay aligned too.**
`scripts/nostalgex-daily-manifest.js` mirrors
`Nostalgex/Nostalgex/Models/SchedulePoolOrdering.swift` and
`Nostalgex/Nostalgex/Services/DailyManifestScheduler.swift`, bit-for-bit, so
web and Apple TV air the same program at the same time.

**Keyword matches are ADDITIVE.** They broaden what a channel can pull in; they
never gate it. Never gate keywords on a broad genre like Drama — it leaks
unrelated titles. `keywordGatedGenres` is the deliberate exception, and it must
only name the channel's own defining genre.

**`rewatched` means `viewCount >= 3`** in both implementations.

**Config version:** 38 · **23 channels** across **6 bundles** · 4 channels whose `id` differs from their `number`.

---

## Bundles

| Bundle | Bundle ID | Channels | Range | Description |
|---|---|---|---|---|
| RETRO CABLE | `nostalgex` | 5 | CH 1–5 | Rewatchables, VHS, DVD and late night, the way cable used to run |
| MOVIES | `movies` | 7 | CH 6–12 | Comedy, action, sci-fi, crime, romance and the greats |
| TV | `tv` | 5 | CH 13–17 | Sitcoms, drama, crime, docs and reality |
| KIDS & FAMILY | `kids` | 2 | CH 18–19 | Cartoons and family movies |
| HOLIDAZE | `tis-the-season` | 2 | CH 20–21 | Christmas movies and specials, every December |
| FRIGHT NIGHT | `seasonal` | 2 | CH 22–23 | Horror and Halloween classics, every October |

Bundled channels: 23 of 23.

---

## Channel exclusivity

Exclusive rules block a title from EVERY channel except the listed channel
`id`s. **Only lock a title to a channel when you are 95%+ sure it belongs
nowhere else — default to overlap.** Over-locking hides content: locking Die
Hard into the holiday channel made it vanish from action for eleven months of
the year.

| Locked to | Matches on | Notes |
|---|---|---|
| CH20 HOLIDAZE (id 130)<br>CH21 CHRISTMAS TV (id 413) | genres Holiday/Christmas, 19 title keywords, 49 editorial titles |  |
| CH18 TOONS (id 18) | genres Anime, TMDB manifest claim (`manifestExclusive`) |  |
| CH22 FRIGHT NIGHT (id 141) | 2 title keywords, 8 editorial titles |  |

---

## Channels by bundle

### RETRO CABLE · CH 1–5

_Rewatchables, VHS, DVD and late night, the way cable used to run_

| CH | `id` | Name | Color | Type | Min items | Rules |
|---|---|---|---|---|---|---|
| 1 | `1` | REWATCHABLES | `#FFE500` | Any | 5 | rewatched (viewCount ≥ 3) |
| 2 | `2` | VHS VAULT | `#E67E22` | Movies | 5 | 1980-1999 · _time: blocks R/NC-17 before 21:00_ |
| 3 | `3` | DVD SHELF | `#C0392B` | Movies | 5 | 2000-2009 |
| 4 | `4` | LATE NIGHT | `#6C1C8A` | Movies | 3 | incl Horror/Thriller, no Family/Kids/Children/Animation/Animated/Comedy, kw +10, kw needs genre Horror/Thriller, kw-gated genre Thriller, 1975-2009, rated R/NC-17/PG-13 · _time: blocks R/NC-17 before 21:00_ |
| 5 | `5` | SATURDAY MATINEE | `#B9770E` | Movies | 5 | incl Western/War/War & Politics/Adventure/Drama/Action +6 more, no Family/Kids/Children/Animation/Animated, 1930-1979 |

### MOVIES · CH 6–12

_Comedy, action, sci-fi, crime, romance and the greats_

| CH | `id` | Name | Color | Type | Min items | Rules |
|---|---|---|---|---|---|---|
| 6 | `6` | LOL | `#F1C40F` | Movies | 5 | incl Comedy, no Horror/War/History/Documentary/Family/Kids/Children, kw excl (3), Plex rating ≥ 6 |
| 7 | `7` | ACTION | `#E74C3C` | Movies | 5 | incl Action/Adventure/War/War & Politics/Western, no Family/Kids/Children/Animation/Animated/Fantasy/Science Fiction/Comedy, kw +17, kw needs genre Action/Thriller/Adventure, 1980-…, ≥ 80 min |
| 8 | `8` | SCI-FI & FANTASY | `#16A0C8` | Movies | 5 | incl Science Fiction/Fantasy, no Animation/Animated/Family/Kids/Children, kw +15, kw needs genre Science Fiction/Fantasy, 1980-… |
| 9 | `9` | CRIME & THRILLERS | `#A93226` | Movies | 5 | incl Crime/Thriller/Mystery, no Horror/Documentary/Family/Kids/Children/Animation/Animated, kw +15, kw needs genre Crime/Thriller/Mystery, 1980-… |
| 10 | `10` | DATE NIGHT | `#FF85A1` | Movies | 5 | incl Romance, no Horror/Thriller/Action/Animation/Animated/Family/Kids/Children, kw +7, kw needs genre Romance |
| 11 | `11` | ALL TIME GREATS | `#FFD700` | Movies | 5 | editorial always-in (44), Plex rating ≥ 8, ≥ 60 min |
| 12 | `12` | FRESH | `#F72585` | Any | 1 | incl Action/Adventure/Animation/Anime/Biography/Cartoon +23 more, added ≤ 30 d ago |

### TV · CH 13–17

_Sitcoms, drama, crime, docs and reality_

| CH | `id` | Name | Color | Type | Min items | Rules |
|---|---|---|---|---|---|---|
| 13 | `13` | SITCOMS | `#9B59B6` | Episodes | 10 | incl Comedy, no Animation/Anime/Kids/Children/Family, kw +7 |
| 14 | `14` | DRAMA TV | `#5A3E8A` | Episodes | 10 | incl Drama, no Animation/Comedy/Documentary/Horror/Reality/Kids/Children/Family, kw +5, kw needs genre Drama |
| 15 | `15` | CRIME TV | `#784212` | Episodes | 3 | incl Crime/Documentary/Docuseries, no Animation/Animated/Family/Kids/Children, kw +21, kw needs genre Crime/Documentary/Docuseries, kw-gated genre Documentary/Docuseries |
| 16 | `16` | DOCS | `#2E86C1` | Any | 5 | incl Documentary/Docuseries/History/Biography, no Animation/Animated/Family/Kids/Children, kw +7, kw needs genre Documentary, kw excl (3) |
| 17 | `17` | REALITY TV | `#FF4500` | Episodes | 5 | incl Reality/Reality-TV/Docuseries/Game Show/Game-Show/Competition |

### KIDS & FAMILY · CH 18–19

_Cartoons and family movies_

| CH | `id` | Name | Color | Type | Min items | Rules |
|---|---|---|---|---|---|---|
| 18 | `18` | TOONS | `#00DD88` | Any | 5 | incl Animation/Animated/Cartoon/Anime/Kids/Children |
| 19 | `19` | FAMILY HOUR | `#00C4FF` | Movies | 5 | needs ALL Family, rated G/PG/PG-13/unrated |

### HOLIDAZE · CH 20–21

_Christmas movies and specials, every December_

| CH | `id` | Name | Color | Type | Min items | Rules |
|---|---|---|---|---|---|---|
| 20 | **`130`** ⚠︎ | HOLIDAZE | `#C41E3A` | Movies | 3 | incl Holiday/Christmas, title allowlist (19), editorial always-in (96), Plex rating ≥ 5 |
| 21 | **`413`** ⚠︎ | CHRISTMAS TV | `#27AE60` | Episodes | 3 | no rules (everything qualifies) |

### FRIGHT NIGHT · CH 22–23

_Horror and Halloween classics, every October_

| CH | `id` | Name | Color | Type | Min items | Rules |
|---|---|---|---|---|---|---|
| 22 | **`141`** ⚠︎ | FRIGHT NIGHT | `#8B0000` | Any | 3 | incl Horror |
| 23 | **`131`** ⚠︎ | SCREAM KIDS | `#FF8C00` | Movies | 3 | no Horror, title allowlist (37), editorial always-in (43), rated G/PG/TV-Y/TV-Y7/TV-G/TV-PG |

---

## `id` ≠ `number`

4 channels whose manifest/exclusivity key (`id`) is not their on-screen number. Marked ⚠︎ in the tables above.

| `id` | Airs as | Name |
|---|---|---|
| `130` | CH20 | HOLIDAZE |
| `413` | CH21 | CHRISTMAS TV |
| `141` | CH22 | FRIGHT NIGHT |
| `131` | CH23 | SCREAM KIDS |

---

_Generated from `channels.json` v38 by `scripts/render-channels-doc.mjs`. Do not edit by hand._
