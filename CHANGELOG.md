# Changelog

All notable changes to this project are documented here. One entry per
merged PR, newest first within each release. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added — Phase 1: Accessibility Service + permission walkthrough

- **`WaSelectors` rewritten as ordered fallback candidates** for both
  `com.whatsapp` and `com.whatsapp.w4b`, covering the compose box, send
  button, conversation title, participant list, and add-participant search,
  plus text fragments for WhatsApp's restriction and privacy-blocked dialogs.
  Kept free of Android imports so its matching rules are unit tested in CI.
- **`NodeFinder`** — walks accessibility node trees trying view-ids first,
  then content-descriptions, then visible text, and reports *which* candidate
  matched so a stale selector is diagnosable rather than merely broken.
- **`WaScreenDump` + Diagnostics screen** — captures a live WhatsApp screen on
  a 10-second delay (the user needs time to switch apps) and renders it as
  shareable text, with a per-selector found/not-found report on top. This is
  what makes architecture doc section 8's "a break is a small patch" promise
  real: repairing a selector no longer needs a laptop and `uiautomatorviewer`.
- **`WaProbe`** — the Phase 1 acceptance test from architecture doc section 9.
  Opens WhatsApp through the documented `wa.me` deep link and confirms the
  service can read the compose box. Never touches the send button, and uses a
  reserved example number so it cannot reach a real person.
- **`ProbeResultPresenter`** — turns each probe outcome into a headline, an
  explanation, and a next step. Unit tested, because this is the client's only
  feedback when setup fails; notably it distinguishes "permission is broken"
  from "permission works, WhatsApp changed its layout", which need opposite fixes.
- **Guided setup walkthrough** (`ui/settings/SetupScreen`) — ordered steps for
  choosing the WhatsApp variant, granting Accessibility, and exempting the app
  from battery optimisation. Includes the **Android 13+ restricted-settings
  step**, which greys out the Accessibility toggle for sideloaded apps; since
  Scope WA ships as a direct-install APK (architecture doc section 4), skipping
  this would make the app look broken on the client's newer phones.
- **`WaServiceBridge`** — observable connection state, distinguishing "enabled
  in Android Settings" from "actually bound and able to read screens".
- **`WaDeepLink`** (in `brain/`) — pure `wa.me` URL builder, unit tested.
- **HomeScreen** now shows live readiness instead of a static placeholder.

### Fixed

- **CI never ran on phase branches.** `ci.yml` triggered only on pushes to
  `main`/`features` and PRs into `main`, but `CLAUDE.md` requires green CI
  *before* a phase branch opens a PR into `features` — so the gate the branch
  policy depends on did not exist, and a PR into `features` ran no checks at
  all. Now runs on every branch and on PRs into both integration branches.
  This blocked Phases 2 and 3 equally.
- CI keeps the HTML test report as an artifact when tests fail.

### Known limitations

- **The WhatsApp view-ids in `WaSelectors` are researched candidates, not
  device-verified.** They have not been confirmed against the client's handsets
  or WhatsApp versions. The ordered-fallback design means a wrong guess
  degrades to the next candidate, and the Diagnostics screen exists to capture
  the real values — but until someone runs the probe on a real phone, Phase 1's
  acceptance criterion is not met. See `MEMORY.md`.

## [0.1.0] — 2026-07-29 — Phase 0: project skeleton

Initial scaffold, committed directly to `main` during project setup.

### Added
- Android project skeleton: Kotlin + Jetpack Compose + Room, Gradle version
  catalog, `com.tricreta.scopewa` package structure matching the layered
  design (`ui/`, `brain/`, `jobrunner/`, `accessibility/`, `data/`, `update/`).
- GitHub Actions CI (`ci.yml`): unit tests + debug build on every push to
  `main`/`features` and every PR into `main`.
- GitHub Actions release pipeline (`release.yml`): signed release APK +
  `update.json`, published to GitHub Releases (gated on signing secrets
  being configured).
- `brain/` pure-Kotlin logic, fully unit tested:
  - `PhoneNormalizer` — E.164 normalisation, default country +254.
  - `TemplateEngine` — CSV variable substitution with fallback chains, plus
    spintax (`{a|b|c}`), matching architecture doc section 6.
  - `UniquenessScorer` — the uniqueness-meter math from section 6.
  - `WarmUpRamp` — the day→daily-cap ramp table from section 6.
  - `PacingPlanner` — Safe/Normal/Fast pacing profiles with randomised delays.
  - `CircuitBreaker` — the five auto-pause conditions from section 6, layer 4.
- Accessibility Service and foreground-service scaffolding
  (`WaAccessibilityService`, `CampaignJobService`, `WaSelectors`) — connection
  plumbing only, no automation logic yet (that's Phase 1).
- In-app updater (`UpdateChecker`) reading the `update.json` shape the
  release pipeline publishes.
- `docs/ARCHITECTURE-V2-WHATSAPP.md` — the full design doc, preserved from
  the client-supplied scratch folder.
- `docs/reference/` — the three proven Chrome-extension blueprints
  (bulk sender, contact extractor, group adder) and the client's annotated
  screenshots, preserved before the original scratch folder is deleted.
- `docs/BUILD-PLAN.md` — phase-by-phase build plan with file ownership and
  dependency graph, so later phases can be worked in parallel by separate
  sessions.
- `CLAUDE.md`, `MEMORY.md` — project governance and running state.
- Public GitHub repository at `wazimuautomate/Scope-WA` (public so CI runs
  without Actions minute limits), with `main` and `features` branches.
  Originally created under `TricretA/Scope-WA` (private) and moved the same
  day — `TricretA` is reserved for personal/website projects,
  `wazimuautomate` is where apps live.

### Fixed
- `TemplateEngine` decided variable-vs-spintax by whether the value map
  happened to contain the key for a given recipient, so a known-but-blank
  variable could randomly resolve as spintax — caught by CI failing on the
  first push (flaky `TemplateEngineTest`). Now decided by an explicit
  `knownVariableNames` set (the CSV headers) instead of the per-row map.

### Notes
- No Room entities yet — `data/` is intentionally empty past a README until
  Phase 2 designs the schema against real requirements.
- No UI screens beyond placeholders (`ComingSoonScreen`) — real screens land
  with their owning phase.
- Not yet buildable end-to-end on a local machine without a JDK/Android SDK
  install; CI is the source of truth until a contributor sets up local tooling.
