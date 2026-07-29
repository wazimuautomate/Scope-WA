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
### Added — Phase 5: Bulk sender

- **Campaign composer** (`ui/campaign/`) — list + template + pacing profile +
  WhatsApp target + schedule + preview + Start, per reference screenshots 08
  and 09. Recurrence is deliberately absent: screenshot 10 is marked SKIP
  because recurring blasts are a fast ban.
- **The preview is the point.** Before anything is written it shows how many
  will be messaged, how many are skipped and why, the uniqueness meter over the
  *actual* rendered messages, the warm-up day and today's cap, and — when the
  queue is bigger than the cap allows — plainly says how many people will not
  be reached today.
- **Live progress screen** (`ui/running/`) — sent/failed/skipped/pending, the
  person currently being messaged, a ticking next-in countdown, and Pause /
  Resume / Stop. Each auto-pause reason is translated into what happened and
  what to do about it.
- **Real foreground service** (`jobrunner/CampaignJobService`) replacing the
  Phase 0 stub: a coroutine loop that asks `CircuitBreaker` before *every*
  send, takes randomised `PacingPlanner` delays and long breaks, re-checks the
  suppression list per recipient, holds a wake lock, and writes progress to
  Room as it goes so a kill costs at most the message in flight.
- **Send routine** (`accessibility/WaSender`) — opens the documented `wa.me`
  deep link with the text prefilled, waits for the compose box, inserts a
  length-proportional typing pause, clicks send, then **verifies the compose
  box cleared** rather than assuming delivery. Watches for WhatsApp's
  restriction wording throughout, which is the one outcome that stops the whole
  campaign instead of counting as a failure.
- **New pure-Kotlin brain pieces** (`brain/campaign/`), all unit tested in CI
  without a phone:
  - `RecipientOrdering` — replied-before first, then saved contacts, then
    strangers; drops opt-outs, suppressed numbers, per-person cooldown and
    in-list duplicates. Ordering means a campaign cut short by a cap or a
    breaker has sent its *safest* messages, not a random slice.
  - `CampaignEngine` — one `nextStep` decision combining pacing and the five
    circuit breakers, with safety evaluated before pacing.
  - `OptOutDetector` — STOP / ACHA / SITAKI / unsubscribe / toa, whole-word,
    with negation and "stop by" guards.
  - `RecipientVariables` — the variable map per recipient, where contact
    identity beats a stale CSV column of the same name.
  - `TypingDelay`, `PacingProfileCatalog`.
- **Room**: `campaigns` and `campaign_messages` filled in with real columns, a
  `CampaignDao`, and a `CampaignRepository`. Messages are rendered when the
  queue is built, so the uniqueness meter scores exactly what will be sent and
  a campaign resumed after a reboot sends what the user previewed.
- The daily cap and warm-up day are computed across **all** campaigns, because
  the cap belongs to the phone number — three campaigns in one day share one
  allowance.

### Fixed — Phase 5

- `CircuitBreaker`'s cold-batch rule (`sentInCurrentBatch >=
  batchSizeForReplyCheck && repliesInCurrentBatch == 0`) is true for all-zeros,
  so a campaign that hadn't opted into reply tracking would have auto-paused
  with `ColdBatchNoReplies` before its first message. Worked around in
  `CampaignEngine` by disabling the rule until a batch is actually being
  counted, rather than changing a Phase 0 rule other phases depend on.

### Known limitations — Phase 5

- **Not device-tested.** `docs/BUILD-PLAN.md` requires a real-phone send test
  for Phase 5; there is no Android SDK or handset in this environment, so this
  is compile-and-unit-test only. It also inherits Phase 1's unverified
  `WaSelectors` view-ids — if those are wrong, sending cannot work.
- **Nothing reads incoming replies yet**, so automatic STOP handling is
  automatic in everything except the noticing: `OptOutDetector` and
  `CampaignRepository.applyOptOut` are built and tested, but no component
  observes messages arriving. That needs a `NotificationListenerService`, which
  this phase does not add. Two consequences: opt-outs are only applied when
  marked by hand (Phase 2's Blocked tab), and the `ColdBatchNoReplies` breaker
  can never fire because reply counts are always zero.
- **No attachments.** The client asked for images/video/audio/documents
  (architecture doc section 10 Q6). The `wa.me` deep link cannot carry them, so
  they need a different send path and are not in this phase.

### Added — Phase 3: Templates

- **Templates screens** (`ui/templates/`) — the editor from architecture doc
  section 7:
  - Saved-template list with a per-template read-out of how many variables and
    spintax blocks it has and how many different messages it can produce.
  - Editor with tappable variable chips (screenshot 11's reference list, but
    they insert at the caret instead of copying to the clipboard) and one-tap
    spintax starters for greetings, lead-ins and sign-offs.
  - **Live preview** cycling through 5 random renders, with a Shuffle button
    and a count of how many of the five actually came out different. All five
    use the same sample recipient on purpose, so anything that differs between
    cards is variation the template itself produces.
  - **Uniqueness meter** in the doc's exact wording —
    `200 messages · 194 unique (97%) · 6 exact duplicates` — with the
    ⚠ warning line, a 100/200/500/1000 campaign-size selector, and a
    combinations count.
  - An editable "columns in your CSV" list. This drives `TemplateEngine`'s
    variable-vs-spintax decision, so `{name|there}` means "name, or *there* if
    blank" rather than a coin flip between the two words.
- **`brain/template/TemplateAnalyzer.kt`** — classifies `{...}` blocks the same
  way `TemplateEngine` renders them, multiplies out spintax combinations
  (capped at 1e9), generates seeded previews, and estimates campaign
  uniqueness. Unit tested, including a test that fails if the analyzer and the
  engine ever disagree about what a block means.
- **`brain/template/TemplateVariables.kt`** — the variable catalogue behind the
  chips, plus clock-derived values (`{date}`, `{day_of_week}`,
  `{random_number}`, …). Screenshot 11's `{LOCATION_*}`/`{BATT}` are
  deliberately absent: they need Android APIs and `brain/` stays Android-free.
- **`brain/uniqueness/UniquenessSummary.kt`** — the meter's wording as tested
  pure functions, since "warn loudly" is a requirement rather than styling.
- **Template persistence** — Phase 2's `TemplateEntity` scaffold filled in with
  `knownVariables` (stored through Phase 2's `Converters`), plus `TemplateDao`,
  `TemplateRepository`, and one `templateDao()` accessor on `ScopeWaDatabase`.
  Exactly the additive shape `docs/BUILD-PLAN.md`'s shared-hotspot rule asks
  for: no new `@Database(entities = [...])` entries.

### Fixed — a crash CI could not see

- **`Regex("\{([^{}]*)}")` crashed on device.** The unescaped closing brace
  compiles fine on the JVM, so every unit test passed and CI was green, but
  Android's ICU-backed regex engine rejects it with `PatternSyntaxException`.
  `TemplateEngine` (Phase 0) carried the identical pattern, so this was never a
  Phase 3 bug — it would have taken out Phase 5's send routine the first time it
  rendered a message on a phone. Fixed in both, with a comment at each site
  since no JVM test can catch it. Found by installing the CI debug APK on an
  emulator.

- The editor's Save bar sat underneath the system navigation bar. `MainActivity`
  draws edge-to-edge and `Scaffold` does not inset a custom `bottomBar`.

### Changed

- Added `androidx.compose.material:material-icons-core` explicitly rather than
  relying on it arriving transitively via material3.

### Notes

- The uniqueness meter reports a **floor**. It holds CSV values constant and
  measures spintax variation only, because the editor cannot know a real contact
  list, and inventing per-recipient names would inflate the score with variation
  the template doesn't actually provide. The UI says so on screen. Phase 5
  recomputes it against the real list before sending.
- **Verified on an emulator, not a phone.** The click-through (list → editor →
  chips → spintax → preview cycling → uniqueness warning → save → reopen) was
  done on an API 30 emulator using the CI debug APK. Templates touch no
  Accessibility APIs, so an emulator is a fair test of this screen; Phase 1's
  probe still needs a real handset.

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
