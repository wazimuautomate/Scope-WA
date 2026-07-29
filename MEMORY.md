# MEMORY.md

Running project state. Update this every session — see `CLAUDE.md`'s memory
discipline section. This is not a changelog (that's `CHANGELOG.md`); it's
"what's true right now and why."

## Current phase

**Phase 0 — done.** Project skeleton, CI, git repo, and governance docs.

**Phase 3 (Templates) — built and emulator-verified, PR #2 into `features`**
(2026-07-29). Template list + editor, variable chips, spintax starters, live
preview cycling 5 renders, uniqueness meter in the doc's exact wording. New
pure-Kotlin `brain/template/TemplateAnalyzer.kt`,
`brain/template/TemplateVariables.kt` and
`brain/uniqueness/UniquenessSummary.kt`, all unit tested. Phase 5 consumes
these; nothing else does yet.

**Phases 2 (Contacts) and 4 (Group extractor) are in progress in parallel
sessions** as of the same day. Phase 4 started ahead of its stated
dependencies.

Still unstarted: Phases 5, 6, 7.

### Phase 3 decisions Phase 5 needs to know

- **`TemplateEntity.knownVariables` is load-bearing, not metadata.**
  `TemplateEngine` decides `{name|there}` means "CSV value, or *there* if blank"
  — rather than a coin flip between two words — by looking `name` up in that
  set. Phase 5 must pass the stored list to the engine at send time, or
  templates render differently than they previewed.
- **The editor's uniqueness meter is a floor, not a prediction.** It holds CSV
  values constant and measures spintax variation only, because the editor has no
  campaign in front of it. Phase 5 must recompute it against the real rendered
  campaign before sending — that is the number section 6 actually describes.
- **Templates is reached from Phase 2's bottom nav bar**, so Phase 3 added no
  entry point of its own.

**Phase 1 — code complete, NOT device-verified.** Accessibility service,
node finding, selector capture tooling, the WhatsApp probe, and the guided
permission walkthrough are all built and CI-green (branch
`phase-1-accessibility`).

> ⚠️ **Phase 1's acceptance criterion is not yet met.** `docs/BUILD-PLAN.md`
> requires it to work "on a real phone", and nothing here has touched one.
> The WhatsApp view-ids in `accessibility/WaSelectors.kt` are researched
> candidates, not captured from a device — they may simply be wrong. **Do not
> treat Phase 1 as done, and do not start Phase 4, 5, or 7, until someone runs
> the in-app test on a real handset.** See "What Phase 1 still needs" below.

**Phase 2 (Contacts) — done, merged into `features` (PR #3, 2026-07-29).** Room schema (all ten tables), CSV/VCF/TXT import with dedupe and
a confirm-before-you-write preview, lists + bulk-select picker, CSV/TXT/VCF/JSON
file export, and the `opted_out` + suppression plumbing Phase 5 needs for STOP
handling. Verified by CI only — compile plus 104 unit tests. No device test was
done and none is needed: Phase 2 touches no Accessibility code. The Compose
screens are compile-checked but have not been clicked through on a handset.

**Phase 3 (Templates)** was still in flight in another session on
`phase-3-templates` when Phase 2 merged. Phase 2 blocks nothing any more, and
`docs/BUILD-PLAN.md` wanted it to land first precisely so Phase 3 only has to
add fields to `TemplateEntity` — **if Phase 3's branch carries a richer one,
take theirs.**

**Phase 4 (extractor) is unblocked on paper but not in practice:** it depends on
Phase 1, and Phase 1's device verification above has not happened. Its
`WaSelectors` view-ids are still researched guesses, so extraction built on them
would be built on sand. Do the 10-minute handset test first.

## What Phase 1 still needs (a human with the phone, ~10 minutes)

1. Install the debug APK from the CI run on a phone that has WhatsApp.
2. Open Scope WA → **Finish setup**, work through the steps, grant Accessibility.
3. Run **Test it**. Then:
   - **"WhatsApp automation is working"** → Phase 1 is genuinely done. Record
     the WhatsApp version it reported here, and phases 4/5/7 are unblocked.
   - **"message box wasn't recognised"** → the permission works but the
     selectors are wrong, which is the expected outcome if the researched ids
     are stale. Use **Diagnostics → Start capture**, switch to a WhatsApp chat,
     share the dump, and correct `WaSelectors.kt` from it. This is a one-line
     fix per selector, by design.
4. Repeat on both WhatsApp and WhatsApp Business, and on both a Samsung and a
   Tecno handset if available — OEM builds differ, and the client uses both
   (architecture doc section 10, Q4).

## Key decisions on record

- **Separate app, not a v2 of the existing SMS/M-Pesa app.** Different risk
  profile (Accessibility Service permission, WhatsApp UI churn) and must
  never take down the money-handling app. See architecture doc section 4.
- **Accessibility Service, not WhatsApp Web/WebView.** Uses the real number
  already logged in, no QR pairing/expiry, works with both WhatsApp and
  WhatsApp Business. See architecture doc section 5.1.
- **Direct-install signed APK via GitHub Releases, not Play Store.**
  Accessibility + bulk messaging would be rejected outright.
- **Extraction is export-only — never writes to the phone's contacts app.**
  Explicit client instruction (architecture doc section 10, Q8).
- **Phase 2 scaffolded all ten Room tables at once** (the eight in architecture
  doc section 5.3 plus `contact_list_members` and `suppression_list`), exactly
  as `docs/BUILD-PLAN.md`'s shared-hotspots section asks. Later phases add
  *fields and DAOs* to their own entity; nobody adds new `entities = [...]`
  entries. `TemplateEntity` is a shell — **if Phase 3's branch defines a richer
  one, take theirs at merge time**; only the `@Database` list has to stay
  single-sourced.
- **`suppression_list` is keyed by phone number, not contact id.** A STOP block
  has to survive the contact being deleted and the same CSV re-imported, which
  is precisely the case where a quietly resurrected opt-out does real damage.
  `contacts.opted_out` and this table are kept in sync by `ContactsRepository`.
- **Contacts keep a `custom_fields` map of the leftover CSV columns.** Section 6
  layer 1 promises any CSV column can be a template variable; without this
  Phase 5 would have nothing to substitute.
- **Room schema export is off and migrations are destructive, deliberately, and
  only until the first release.** Nothing has shipped, so there's no user data
  to migrate and a committed schema would just record guesses later phases
  change. **Before the first signed release:** turn `exportSchema` on, add the
  `room.schemaLocation` KSP arg, commit the schema, drop
  `fallbackToDestructiveMigration()`.
- **Pure logic lives outside `brain/` when it belongs to a feature.** The
  contact parsers/importer/exporter sit in `data/repository/contacts/` (Phase 2's
  owned directory) but have zero Android imports, so CI still tests them without
  a phone. `brain/` stays the cross-cutting anti-ban logic; the `brain/` rule in
  `CLAUDE.md` is "no Android in brain", not "all pure code in brain".
- **Build order is deliberate: extractor → sender → adder.** Risk increases
  in that order; each phase teaches the Accessibility techniques the next
  needs. Group adder (Phase 7) ships last on purpose.
- **Repo/branch structure:** `main` (releasable) ← `features` (integration)
  ← per-phase branches. Enforced in `CLAUDE.md`. Initial scaffold commit
  went directly to `main`, as a one-time exception before the policy applied.
- **Repo lives at `wazimuautomate/Scope-WA`, public.** Moved same-day from
  `TricretA/Scope-WA` (private) — `TricretA` is the owner's personal/website
  projects account, `wazimuautomate` is where apps are stored. Public so CI
  minutes aren't capped. Nothing sensitive belongs in this repo as a result —
  no real client phone numbers, no signing keystores (already gitignored),
  no API keys in plaintext.

## Open questions (from architecture doc section 10, some already answered by the client)

All answered as of the architecture doc's writing (2026-07-29):
1. Both WhatsApp and WhatsApp Business must be supported.
2. Client will use a dedicated second number for campaigns.
3. Up to 20k total contacts; target ≤5,000 reached per day.
4. Runs on a dedicated phone (Samsung/Techno available), untouched during a campaign.
5. Real message example is in architecture doc section 10, Q5 — use it as the
   template test fixture rather than inventing a new one.
6. Attachments needed: images, video, audio, documents, optionally captioned.
7. 150+ groups, 700+ members each — extraction needs to handle that scale
   (pagination/scroll performance matters).
8. Extracted contacts are exported as CSV only, never saved to the phonebook.

Nothing outstanding from the client as of Phase 0. If a later phase surfaces
a new open question, add it here with the date it came up.

Raised by Phase 2 (2026-07-29), not blocking:
- Q8 says extraction exports "as CSV files". Phase 2 also implements TXT, VCF
  and JSON export because the reference extension had them and they're nearly
  free. **XLSX is deliberately not implemented** — it needs a ZIP writer and
  belongs with Phase 4, where the client actually asked for it. Worth
  confirming with him that CSV is the format he'll really use before Phase 4
  spends effort on the other four.

## Things learned while building (don't rediscover these)

- **Android's regex engine is stricter than the JVM's.** `Regex("\{([^{}]*)}")`
  compiles on the JVM — so unit tests pass and CI goes green — but Android's
  ICU-backed engine throws `PatternSyntaxException` on the unescaped `}`. This
  shipped in `TemplateEngine` from Phase 0 and crashed the app the first time a
  template screen opened on a device. **Escape both braces**, and treat "CI is
  green" as no evidence at all about regex literals. Nothing in the JVM test
  suite can catch this class of bug; only running the APK can.
- **`Scaffold` does not inset a custom `bottomBar`.** With `enableEdgeToEdge()`,
  a screen-level bottom bar renders underneath the system navigation bar unless
  something insets it. Since Phase 2 added `MainActivity`'s outer `Scaffold` +
  bottom nav, the outer one now handles it — so a nested screen must *not* add
  `navigationBarsPadding()` as well, or it leaves a dead gap.

- **Android 13+ blocks Accessibility for sideloaded apps.** The toggle appears
  but is greyed out until the user does App info → ⋮ → *Allow restricted
  settings*. Scope WA is direct-install by design (architecture doc section 4),
  so this hits every install on a modern phone. The setup walkthrough covers
  it; don't remove that step thinking it's redundant.
- **"Enabled in Settings" ≠ "connected".** The service can be ticked in
  Android Settings without being bound (briefly after toggling, and after a
  force-stop on some OEM builds). Readiness checks must use
  `WaServiceBridge.isConnected`, not the Settings value.
- **Content-descriptions are localised.** "Type a message" doesn't exist on a
  Swahili phone. Anything on the critical send path needs a view-id candidate;
  there's a unit test enforcing this.
- **`URLEncoder` breaks wa.me links.** It form-encodes spaces as `+`, which
  WhatsApp renders literally — messages arrive with plus signs between every
  word. `WaDeepLink` converts to `%20`; there's a test pinning it.
- **CI didn't run on phase branches** until Phase 1 fixed `ci.yml`. If a
  branch seems to have no checks, that's the shape of the bug to look for.
- **Android's own contact export writes quoted-printable.** Any name with an
  accent or non-Latin character comes out as
  `N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:...`. `VcfParser` decodes it; a
  parser that doesn't will import visible mojibake, not an obvious crash.
- **Room 2.6's `fallbackToDestructiveMigration()` takes no arguments.** The
  `dropAllTables = true` overload is 2.7+. Easy to write from memory and it
  fails at compile time, which is at least fast.

## Known risks to keep front of mind

- Ban risk is reduced, not eliminated, at any volume — this must stay
  visible in the product (see architecture doc section 2), not just in docs.
- WhatsApp UI changes are a "when," not "if" — that's what
  `accessibility/WaSelectors.kt` centralizing selectors is for.
- WhatsApp's LID rollout hides some group members' numbers; how many is
  per-group and unknowable until Phase 4 extraction runs against the
  client's actual groups.

## Environment notes

- The Android SDK **is** installed locally (`~/AppData/Local/Android/Sdk`), but
  there is **no JDK and no Gradle** on this machine as of 2026-07-29, and the
  repo has no Gradle wrapper. Nothing can be compiled locally — every build and
  test result comes from GitHub Actions CI. Installing a JDK 17 would be the
  single highest-value local change; until then, expect a push-and-wait loop for
  every compile error.
- **You can still run the app locally**, and you should. `emulator` and `adb`
  need no JDK, and there is an AVD called `scope_test` (API 30). Download the
  debug APK from the CI run (`gh run download <id> -n scope-wa-debug`) and
  install it. Phase 3 found a crash this way that CI is structurally unable to
  catch — see the regex note below.
- **Git Bash rewrites device paths.** `adb shell uiautomator dump /sdcard/ui.xml`
  silently writes to a Windows path unless `MSYS_NO_PATHCONV=1` is set — but
  that same variable then breaks local paths passed to `adb install`, which need
  `cygpath -w`.
- **The emulator is a shared resource.** Parallel phase sessions install over
  each other's builds (same `com.tricreta.scopewa.debug` package). Reinstall
  before trusting what is on screen.
- `gh` CLI has multiple accounts authenticated locally (`TricretA`,
  `wazimuautomate`, `Wazimu90`); active account must be `wazimuautomate` for
  this repo (`gh auth switch --hostname github.com --user wazimuautomate`).
- **Parallel sessions share one checkout — use `git worktree`.** Two sessions
  running in `C:\Users\ADMIN\OneDrive\Desktop\Scope WA` at once will fight over
  the branch and each other's uncommitted files (this happened on 2026-07-29:
  a `git checkout -b` moved the branch out from under another session's
  in-progress edits). Phase 2 and Phase 3 each ran from
  `git worktree add <dir> -b phase-N-<name> origin/features`. Do that.
- `ci.yml` runs on **every** branch push and on PRs into `main`/`features`
  (Phase 1 fixed this). Pushing a phase branch is enough to get a check;
  `gh run list --branch <name>` finds it.
