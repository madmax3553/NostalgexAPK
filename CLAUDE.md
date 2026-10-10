# Working in this repo

Nostalgex: a retro TV guide for Plex, Jellyfin and Emby. This repo is a **fork**
of the Apple TV project and is being ported to **Android TV**. It also holds the
marketing site and the browser web tuner. This repo is **public**. Anything
written here, including the dev log, is readable by anyone.

## Surfaces and status

| Surface | Status | Location |
|---------|--------|----------|
| **tvOS app** (Swift) | Shipping upstream. Reference implementation for the port. Frozen except for fixes that must go to both platforms. | `Nostalgex/` |
| **Android TV app** (Kotlin) | In development on this fork. Target: feature parity with the tvOS app. | `android/` |
| **Web tuner** | Live. Shares channel rules with both native apps. | `plex-tuner.html`, `app.js`, `scripts/nostalgex-channel-filter.cjs` |
| **Marketing site** | Live on Vercel. | `index.html` and friends |

## Android TV port

- The Swift code is the **spec**. When behaviour is unclear, read the Swift
  source and its tests before inventing anything. Port behaviour, not structure.
- Don't redesign the guide, schedule or channel logic during the port. Parity
  first, improvements after.
- The Android app lives in `android/` and is built by Gradle. Keep it out of the
  Xcode project and vice versa.
- Target Android TV (leanback). Remote-only navigation is required: every screen
  must be reachable and operable with a D-pad and no touch.
- Version the Android app with `versionName` and `versionCode` in
  `android/app/build.gradle`. Dev log entries name these the way tvOS entries
  name marketing version and build number.
- Secrets for signing (keystore, passwords) never go in the repo. Use
  `local.properties` or environment variables, and keep them out of git.
- Library, playback and server code is shared in behaviour with tvOS. Plex,
  Jellyfin and Emby each need their own handling; check the Swift code for the
  endpoint quirks already found there before writing new requests.

## Dev log: required at the end of every session

Before finishing a session in which anything changed, append an entry to
`docs/DEVLOG.md`. Commits say what changed; the log says **why**, and what state
things were left in. It is read by Chad and by his agent, so it has to stand on
its own without the conversation around it.

Newest entry goes at the top, under the heading, in this shape:

```markdown
## 2026-10-08

**Shipped.** What actually went out, and where. Name versions and build numbers.

**Changed.** The work that landed on main, one line each, with the reason rather
than the diff. "Emby libraries come from /Users/{id}/Views now, because
/UserViews is Jellyfin's spelling and 404s on Emby" beats "fixed endpoint".

**Half-done.** Anything on a branch, behind a flag, or merged but unverified.
Name the branch. Say what would finish it.

**Next.** What the next session should pick up, and anything blocked on a person
rather than on code.
```

`docs/DEVLOG.md` is **public and carries the real detail**: what changed and why,
measured numbers, branch names, what is half-done, what is unverified, and credit
to contributors. Chad's agent reads it from GitHub, so it has to be complete
there, not a pointer to somewhere private.

Four things never go in it. They go in
`projects/nostalgex-internal/docs/DEVLOG-INTERNAL.md` instead:

- **Anyone who did not opt in to being public.** TestFlight testers, Play
  testers, people who emailed support, anyone who is not already a participant
  in the GitHub thread. Describe the report, not the person: "a tester on
  Android TV 14, Nvidia Shield", never their name.
- **Keys, tokens, server addresses**, keystore details, local paths that reveal
  a home network.
- **Unreleased plans** meant to stay quiet: release timing, marketing sends,
  pricing, anything commercial.
- **Personal data**, including the contents and size of Chad's own libraries.
  Behavioural measurements are fine once detached from whose server they came
  from: "a test library of 1,131 music videos" rather than "Chad's library".

Rules for the entry:

- Write what a stranger needs, not what the session remembers. No "as discussed".
- Measured numbers beat adjectives. "0.72x real time, 14,612 requests in 8s" is
  the useful part.
- Say plainly when something is unverified. "Not measured against an Emby
  server" is worth more than silence.
- Keep it to the length the day earned. A one-line day gets one line.
- No em dashes, no emoji. Match the tone of the rest of the docs.

## Before changing playback or the guide

These are the product. A regression here is worse than a missing feature, and
both have been broken by well-meant fixes before. This applies to the Android
port as much as to the Swift app.

- Never diagnose by reasoning alone. Build a harness, measure, then name a cause.
- Parity check: when an Android behaviour is ported or changed, compare it against
  the Swift implementation on the same inputs (same channels.json, same library
  fixtures) before calling it done.
- Swift tests must keep passing while the Swift app is still maintained:
  `xcodebuild test -project Nostalgex/Nostalgex.xcodeproj -scheme Nostalgex \
   -destination 'platform=tvOS Simulator,name=Apple TV 4K (3rd generation)' \
   -only-testing:NostalgexTests CODE_SIGN_IDENTITY="-" \
   CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO`
- Android unit tests must pass. Run them with
  `./gradlew -p android :app:testDebugUnitTest` (confirmed 2026-10-10). Needs
  JDK 17 (`JAVA_HOME`) and the Android SDK at `~/Android/Sdk`, which is set in
  `android/local.properties` (git-ignored). Gradle 8.10.2 is pinned by the wrapper.
- `channels.json` is shared by the Swift app, the Android app and the web tuner
  (`scripts/nostalgex-channel-filter.cjs`). Change all of them or they drift.
  Any change to `channels.json` or filter semantics needs the smoke scripts in
  `scripts/` (`nostalgex-filter-smoke.mjs`, `nostalgex-schedule-order-smoke.mjs`)
  run against the new rules.
- Generated regions of `index.html` come from `content/changelog.json` and
  `channels.json` via the scripts in `scripts/`. Never hand-edit between the
  markers; edit the source and re-render.
