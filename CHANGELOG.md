# Changelog

All notable changes to this project are documented here. One entry per
merged PR, newest first within each release. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Changed — Phase 4 follow-up: capture every group member by default

- `ExtractionFilters.excludeWithoutNumbers` now defaults to **false**. The
  first cut of this screen dropped members WhatsApp doesn't show a number for
  (saved contacts) by default, which contradicted the actual goal: a complete
  record of who's in the group. They're now kept by default, as name-only
  rows — turning the filter on is an opt-in choice to hide them, not the
  starting behaviour. `exportMerged()`'s `includeUnreachable` default flipped
  to match.
- **What changed vs. what can't:** a member's display name can always be
  filled in (falls back to their number, or now also to content-description
  when a layout exposes the label that way) — that part is fixed. A phone
  number for someone WhatsApp only shows a saved name for **cannot be
  recovered**; `ContactEntity.phoneE164` is a required, unique column, so a
  numberless "contact" has nothing to be identified or messaged by. That
  distinction is now explicit in the screen copy instead of implied.
- **Wired an actual export button.** The original screen's copy claimed
  extraction results were "exported as files", but no UI action produced one —
  a real gap. `FinishedCard` now has CSV/VCF/JSON export buttons using Phase
  2's exact SAF file-picker pattern (`ContactFileIo`, `PendingExport`).

### Added — Phase 4: group contact extractor

- **`MemberRowParser`** — turns a rendered participant row into a member.
  Deliberately strict about what becomes a phone number: an "about" text
  reading *"call me on 0712345678"* must not be harvested as that member's
  number, because the consequence is messaging the wrong person.
- **`ExtractionFilter`** — exclude admins / already-saved / unreadable / self,
  reporting **per-reason drop counts**. "824 read → 310 kept" is alarming
  unexplained and unremarkable once it reads "310 kept, 400 numbers not shown,
  114 already saved".
- **`ExtractionMerger`** — dedupes across groups by number, tracking every
  group a person appeared in. Members without a readable number are never
  merged on name: two people both rendering as "John" are not the same person,
  and collapsing them would silently delete someone.
- **`GroupExtractor`** — scrolls the virtualised participant `RecyclerView`,
  stopping after repeated barren passes, and records WhatsApp's own
  "N participants" figure so an early-stopped scroll is detectable rather than
  presenting as a complete extraction.
- **`ExtractionRepository`** — saves through Phase 2's import path, so
  extraction inherits phone normalisation, dedupe and, critically,
  **suppression-list enforcement**: someone who replied STOP must not re-enter
  the database because a group they're in got extracted.
- **`ExtractionDao`** and extraction history; `ExtractionEntity` filled in with
  reported-vs-read counts.
- **Extract screen** (`ui/extract/`) with delayed capture, live progress,
  filters, honest result counts, and a bottom-bar entry.
- Export reuses Phase 2's `ContactExporter` — including its `hidden` handling —
  so extraction and contact exports are byte-identical in format and both
  produce **files only, never phonebook entries**.

### Changed — Phase 4

- `ContactsRepository.applyImport` takes an optional `sourceGroup`, populating
  the `ContactEntity.sourceGroup` column Phase 2 scaffolded for this phase.
- `ContactsRepository.knownNumbers()` added for the "skip people I already
  have" filter.
- `CLAUDE.md` documents the emulator workflow and now **requires every phase to
  be run on the emulator before its PR**, with screenshots.

### Known limitations — Phase 4

- **Accessibility can only read rendered text, so numbers are recoverable only
  for members not already saved on the phone.** The Chrome extension in
  `docs/reference/` reads WhatsApp Web's internal store and gets a number for
  everyone; the Android app has no equivalent. Extracted count will therefore
  be lower than member count. Documented in `MemberNumberStatus`, surfaced in
  the UI, and counted honestly rather than hidden.
- A saved contact and a LID-hidden member are **indistinguishable** from a
  rendered row, so the two are modelled as one status (`NotShown`).
- The group-info selectors are researched candidates like Phase 1's, and **no
  WhatsApp interaction has been verified on a device** — the emulator has no
  WhatsApp and cannot realistically have one.
- **The "capture every member by default" follow-up is CI-verified only, not
  re-confirmed on the emulator.** The shared emulator was unresponsive under
  host memory pressure (0.47 GB free) when this was ready to test — see
  `MEMORY.md`. The change (a filter default flip plus additive export
  buttons) doesn't touch anything the first Phase 4 pass already confirmed
  renders correctly, but the export button itself has not been clicked on a
  device.

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

### Fixed — Phase 1

- **CI never ran on phase branches.** `ci.yml` triggered only on pushes to
  `main`/`features` and PRs into `main`, but `CLAUDE.md` requires green CI
  *before* a phase branch opens a PR into `features` — so the gate the branch
  policy depends on did not exist, and a PR into `features` ran no checks at
  all. Now runs on every branch and on PRs into both integration branches.
  This blocked Phases 2 and 3 equally.
- CI keeps the HTML test report as an artifact when tests fail.

### Known limitations — Phase 1

- **The WhatsApp view-ids in `WaSelectors` are researched candidates, not
  device-verified.** They have not been confirmed against the client's handsets
  or WhatsApp versions. The ordered-fallback design means a wrong guess
  degrades to the next candidate, and the Diagnostics screen exists to capture
  the real values — but until someone runs the probe on a real phone, Phase 1's
  acceptance criterion is not met. See `MEMORY.md`.

### Added — Phase 2: Contacts

- **Room schema.** All eight tables from architecture doc section 5.3 are
  registered in `ScopeWaDatabase` in one go, as `docs/BUILD-PLAN.md` asks, so
  later phases add fields and DAOs rather than new `@Database` entries.
  `contacts`, `contact_lists`, `suppression_list` and the `contact_list_members`
  join are fully built and used; `templates`, `campaigns`, `campaign_messages`,
  `group_add_jobs`, `extractions` and `settings` are documented scaffolds owned
  by the phase named in each entity's KDoc.
  - Two tables the section 5.3 prose doesn't name were needed: the
    contacts↔lists join, and `suppression_list`. The latter is keyed by number
    rather than contact id so a STOP block survives a contact being deleted and
    the same CSV re-imported.
  - `contacts.custom_fields` keeps the leftover CSV columns (`town`,
    `last_bundle`, …). Without it the CSV-variable half of section 6 layer 1
    would have nothing to substitute in Phase 5.
- **CSV / VCF / TXT import**, ported to Kotlin from the proven Chrome
  extensions in `docs/reference/` (`lib/parse.js`, `lib/csv.js`). All parsing is
  pure Kotlin with no Android imports, so CI tests it without a phone.
  - CSV: RFC-4180-ish — quoted fields, embedded commas and newlines, escaped
    quotes, CRLF/LF/CR, BOM. Duplicate and blank headers are renamed rather than
    silently swallowing a column.
  - VCF: adds quoted-printable decoding (what Android's own contact export
    produces for any accented name), every `TEL` line rather than only the
    first, and Apple-style `item1.TEL` grouping.
  - TXT: `number`, `number,name`, `name,number`, tab- and semicolon-separated.
- **Dedupe and normalisation** through the existing Phase 0 `PhoneNormalizer`.
  An import is planned before it is applied: the user sees new / already-known /
  duplicate-in-file / blocked / unusable counts and confirms, instead of finding
  out after 20,000 rows have landed.
- **Lists, bulk-select picker and export** — the screens from reference
  screenshots 02, 03 and 04. Export writes CSV, TXT, VCF or JSON **files** via
  the Storage Access Framework; per the client's answer in section 10 Q8,
  nothing is ever written to the phone's address book.
- **`opted_out` flag and suppression list** (section 6 layer 3), ready for
  Phase 5's STOP/ACHA/SITAKI handling. Suppressed numbers are dropped on import,
  not imported and flagged.
- A minimal bottom navigation bar, because the Phase 0 skeleton starts on Home
  and Home had no links — the Contacts screens were otherwise unreachable.
  Deliberately throwaway; Phase 1 should replace it when Home lands.

### Changed — Phase 2

- Added `androidx.lifecycle:lifecycle-viewmodel-compose` to the version catalog.

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
