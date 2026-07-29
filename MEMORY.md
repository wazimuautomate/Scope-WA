# MEMORY.md

Running project state. Update this every session — see `CLAUDE.md`'s memory
discipline section. This is not a changelog (that's `CHANGELOG.md`); it's
"what's true right now and why."

## Current phase

Statuses as of 2026-07-29. Phases 1, 2, 3 and 5 were built the same day in
parallel sessions, which is why several say "code complete, not verified".

| Phase | State |
| --- | --- |
| 0 — skeleton, CI, governance | **Done**, on `main` |
| 1 — Accessibility service | Code complete, merged to `features`. **Not device-verified.** |
| 2 — Contacts | **Done**, merged to `features` (PR #3) |
| 3 — Templates | Code complete, emulator-verified, PR #2 open into `features` |
| 4 — Group extractor | Code complete, merged with `features`, PR #5 open. **WhatsApp side not device-verified.** |
| 5 — Bulk sender | Code complete, PR into `features`. **Not device-verified.** |
| 6 — Activity log / reports | Not started |
| 7 — Group adder | Not started, ships last on purpose |

> ⚠️ **One handset test gates everything that touches WhatsApp.** The view-ids
> in `accessibility/WaSelectors.kt` are researched candidates that have never
> been captured from a real device — they may simply be wrong. Phases 4, 5 and 7
> all send or read through them, so none of them can be called working until the
> 10-minute test below passes. Do it before writing any more automation.

### What that handset test is (a human with the phone, ~10 minutes)

1. Install the debug APK from any green CI run on a phone that has WhatsApp.
2. Open Scope WA → **Finish setup**, work through the steps, grant Accessibility.
3. Run **Test it**. Then:
   - **"WhatsApp automation is working"** → Phase 1 is genuinely done. Record
     the WhatsApp version it reported here, and phases 4/5/7 are unblocked.
   - **"message box wasn't recognised"** → the permission works but the
     selectors are wrong, which is the expected outcome if the researched ids
     are stale. Use **Diagnostics → Start capture**, switch to a WhatsApp chat,
     share the dump, and correct `WaSelectors.kt` from it. One line per
     selector, by design.
4. Repeat on both WhatsApp and WhatsApp Business, and on both a Samsung and a
   Tecno handset if available — OEM builds differ and the client uses both
   (architecture doc section 10, Q4).
5. **Then, and only then**, Phase 5: send to two or three test numbers you own
   and watch the pacing before pointing it at a real list.

### Phase 5 specifics

Composer, send routine, real foreground service, live progress and the
`brain/campaign/` decision logic are built and CI-green (217 unit tests). Its
BUILD-PLAN acceptance criterion — "a manual device test sending to a small set
of real test numbers with visibly randomised pacing" — is **not** met.

**Phase 5 carries Phase 3 in its history.** Phase 5 depends on Phase 3 and
Phase 3 had not merged yet, so `phase-3-templates` was merged into
`phase-5-bulk-sender`. Both branches had independently scaffolded the whole Room
schema; the merge kept Phase 2's per-file entities, took Phase 3's richer
`TemplateEntity`, deleted Phase 3's duplicate `entity/Shells.kt`, and aliased
`ScopeWaDatabase.getInstance()` to `get()` so neither phase's call sites needed
rewriting. **Phase 3's PR #2 should still land on its own merits.**

### Phase 3 decisions that Phase 5 depends on

- **`TemplateEntity.knownVariables` is load-bearing, not metadata.**
  `TemplateEngine` decides `{name|there}` means "CSV value, or *there* if blank"
  — rather than a coin flip between two words — by looking `name` up in that
  set. Phase 5 passes the campaign's real variable names at render time via
  `RecipientVariables.knownNames`.
- **The editor's uniqueness meter is a floor, not a prediction.** It holds CSV
  values constant and measures spintax variation only. Phase 5 recomputes it
  against the real rendered campaign in `CampaignRepository.preview`, which is
  the number architecture doc section 6 actually describes.

### Phase 4 specifics

**Phase 4 (Group extractor) — code complete, WhatsApp interaction NOT
verified.** Member-row parsing, filters, cross-group dedupe, the scroll
routine, persistence, export and the Extract screen are built and CI-green
(branch `phase-4-extractor`, PR #5). The screen, filter state and readiness
guard **were verified on the emulator**; the WhatsApp side was not, and could
not be.

> ⚠️ **Phase 4 was built while Phase 1 was still unverified**, contrary to the
> caution above. That was a deliberate call to keep parallel sessions moving,
> and the risk is real and unchanged: Phase 4's group-info selectors sit on the
> same unverified foundation. Both get confirmed in the same 10-minute device
> session — Phase 1's probe first, then one real group extraction.

#### The constraint Phase 4 discovered (matters for client expectations)

Accessibility can only read **rendered text**. WhatsApp shows a participant's
**phone number only when that person is not already saved on the phone**;
for saved contacts it renders the saved name instead. The Chrome extension in
`docs/reference/` did not have this problem because it read WhatsApp Web's
internal store, which carries a dialable number for every member.

**Consequence: extracted numbers will be fewer than group members**, by however
many members the client already has saved. For harvesting *unknown* numbers —
the actual goal — this is the useful direction, but the client was quoted
against a tool that got everyone, so it is worth saying out loud before he
tests it. The UI states it up front and the counts are reported honestly rather
than quietly dropping rows.

A saved contact and a LID-hidden member look identical on screen, so the two
are modelled as a single "number not shown" status instead of guessing.

**Follow-up (2026-07-29, same day):** the client-facing framing above was
right, but the *default behaviour* wasn't matching it — `ExtractionFilters`
excluded numberless members by default, so the "complete record" the UI
promised wasn't actually what came out. Fixed: all read members are kept by
default now (name-only for the ones with no number), and export defaults to
including them too. **This does not change the hard limit** — a number that
was never rendered on screen still can't be recovered, and can't be stored as
a contact (`ContactEntity.phoneE164` is required + unique). What changed is
that those members are no longer silently dropped; they're captured with a
name and included in every export, just not messageable. Also: the export
button was simply missing from the screen before this — added, reusing Phase
2's file-picker pattern exactly.

**Merged with `features` on 2026-07-29** (Phase 3 + Phase 5 had landed
meanwhile). The extract route sits alongside the campaign route in the nav host
and the bottom bar; `ScopeWaDatabase` carries `templateDao()`, `extractionDao()`
and `campaignDao()` together.

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
- **The send queue is frozen when a campaign is created, not derived at send
  time.** Recipient order, and the rendered text for every person, are written
  into `campaign_messages` up front. That makes the uniqueness meter honest (it
  scores the exact strings that will go out), makes a campaign resumed after a
  reboot send what the user previewed, and stops the queue reshuffling
  underneath a half-finished run.
- **Recipient ordering is replied-first, then saved, then strangers** — and the
  ordering matters *because* campaigns get cut short. When a cap or a circuit
  breaker stops a run, the messages that already went are the safest ones.
- **The daily cap belongs to the phone number, not the campaign.** Three
  campaigns in one day share one allowance; `sentToday` is computed across all
  `campaign_messages`.
- **A skip is never a failure.** Opt-outs, suppression and cooldown skips do not
  count toward the consecutive-failure breaker — pausing a campaign for doing
  the right thing would be backwards.
- **`CircuitBreaker`'s cold-batch rule is true for all-zero input** (`0 >= 0 &&
  0 == 0`), so a campaign would auto-pause with `ColdBatchNoReplies` before its
  first message. `CampaignEngine` disables the rule until a batch is genuinely
  being counted rather than editing a Phase 0 rule other phases depend on. If
  reply tracking is ever wired up, revisit this.
- **Delivery is verified, not assumed.** `WaSender` treats a message as sent
  only when the compose box clears afterwards. Without that check a campaign
  against a restricted account would report a clean 100%.
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

Raised by Phase 5 (2026-07-29), **blocking for the feature the client asked for**:
- **Nothing reads incoming WhatsApp replies.** `OptOutDetector` and
  `CampaignRepository.applyOptOut` are built and tested, but no component
  observes messages arriving, so automatic STOP/ACHA/SITAKI handling is
  automatic in everything except the noticing. It needs a
  `NotificationListenerService` (another scary permission, another walkthrough
  step) or reading the chat list via the Accessibility service. Until then
  opt-outs only happen when marked by hand, and the `ColdBatchNoReplies`
  circuit breaker can never fire because reply counts are always zero. **This
  is a real gap against architecture doc section 6 layer 3 — decide the
  approach before Phase 6.**
- **Attachments are not implemented.** The client asked for images, video,
  audio and documents, optionally captioned (section 10 Q6). The `wa.me` deep
  link can only carry text, so attachments need a different send path
  (share-intent into WhatsApp, then drive the picker). Not in Phase 5's
  BUILD-PLAN scope, but the client did ask for it — schedule it explicitly.

Raised by Phase 2 (2026-07-29), not blocking:
- Q8 says extraction exports "as CSV files". Phase 2 also implements TXT, VCF
  and JSON export because the reference extension had them and they're nearly
  free. **XLSX is deliberately not implemented** — it needs a ZIP writer and
  belongs with Phase 4, where the client actually asked for it. Worth
  confirming with him that CSV is the format he'll really use before Phase 4
  spends effort on the other four.

## Emulator (added 2026-07-29, updated same day)

**The emulator went down entirely between sessions and had to be relaunched**,
then became unresponsive under memory pressure once booted (a `screencap`
call timed out after 40s; host free RAM was 0.47 GB with no other session's
`uiautomator` activity visible — this was host memory exhaustion, not
contention). This machine cannot reliably sustain the emulator alongside
everything else running on it. If you hit this: check free RAM before
assuming a test failure is your code's fault, and don't fight a struggling
instance — wait or come back rather than repeatedly restarting it, which
risks making things worse for whoever else needs it.


There is a working AVD, `scope_test` (Android 11 / API 30), at
`C:\Users\ADMIN\AppData\Local\Android\Sdk`. **`CLAUDE.md` has the full
workflow and now requires every phase to run on it before its PR.** Three
things that cost time to work out:

- **There is no local JDK**, so nothing builds locally. The loop is: push →
  let CI build → `gh run download` the debug APK → `adb install`.
- **Always `adb uninstall` first.** Each session's CI build is signed with a
  different debug keystore, so `install -r` fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` when another session installed last.
- **The emulator is shared and RAM is very tight** (~0.6 GB free, so a second
  AVD is not viable). Concurrent sessions polling with `uiautomator` and
  force-stopping apps will reset your accessibility settings and kill your app
  mid-test — which looks exactly like a bug in your code. Check
  `adb logcat -d | Select-String uiautomator` before believing a surprising
  result.

## Things learned while building (don't rediscover these)

- **Android's regex engine is stricter than the JVM's.** `Regex("\{([^{}]*)}")`
  compiles on the JVM — so unit tests pass and CI goes green — but Android's
  ICU-backed engine throws `PatternSyntaxException` on the unescaped `}`. This
  shipped in `TemplateEngine` from Phase 0 and crashed the app the first time a
  template screen opened on a device. **Escape both braces**, and treat "CI is
  green" as no evidence at all about regex literals. Nothing in the JVM test
  suite can catch this class of bug; only running the APK can.
- **`Scaffold` does not inset a custom `bottomBar`.** With `enableEdgeToEdge()`
  in `MainActivity`, a bottom bar needs `Modifier.navigationBarsPadding()` or it
  renders underneath the system navigation bar.

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
