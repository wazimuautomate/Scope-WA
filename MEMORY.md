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
| 4 — Group extractor | In progress in another session |
| 5 — Bulk sender | Code complete, merged to `features`. Reply listener added as a follow-up PR. **Not device-verified.** |
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
`brain/campaign/` decision logic are built and CI-green. Its
BUILD-PLAN acceptance criterion — "a manual device test sending to a small set
of real test numbers with visibly randomised pacing" — is **not** met.

The follow-up added reply reading: `accessibility/WaNotificationListener` plus
pure `brain/reply/` (`ReplyNotificationParser`, `ReplyRouter`), reply columns on
`campaign_messages` / `contacts`, and the re-enabled `ColdBatchNoReplies`. **The
notification heuristics are the least verified code in the repo** — WhatsApp's
notification shapes were not captured from a handset, and no unit test can tell
you what a real Samsung running Swahili actually posts. When the handset test
below happens, add a step: reply to a test campaign from a saved contact, from
an unsaved number, and from a group, and check the opt-out lands.

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
- **Room schema is at version 2.** The reply listener added
  `campaign_messages.replied_at` / `reply_count` and `contacts.last_replied_at`.
  Bumping the version rather than editing v1 in place is what keeps
  `fallbackToDestructiveMigration()` from throwing an identity-hash error on a
  dev phone that already has the old database.
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
  being counted rather than editing a Phase 0 rule other phases depend on.
  **Revisited by the reply-listener work (2026-07-29):** the rule is on again,
  now gated on `EngineState.replyTrackingAvailable` *as well as* a started
  batch. `CampaignJobService` sets that from
  `NotificationPermission.isGranted`, re-read every loop iteration so revoking
  the permission mid-campaign disables the rule instead of pausing the run.
  Ungranted → the reply count is zero by construction, which says nothing about
  the list, so the rule must stay off. `CircuitBreaker` itself is still
  untouched.
- **Replies are read with a `NotificationListenerService`, not by scraping the
  chat list with the Accessibility service** (2026-07-29). This was the last
  open item blocking architecture doc section 6 layer 3. The Accessibility
  service can only read a screen that is *on screen*, and the campaign phone is
  meant to sit untouched for hours (section 10, Q4) — so chat-list reading
  would have meant either driving WhatsApp to the foreground on a timer, which
  is exactly the visible robotic behaviour the pacing layer exists to avoid, or
  seeing none of the replies. The trade-offs accepted:
  - **A second scary permission, and a phone-wide one.** Android cannot scope
    notification access to a single app the way `accessibility_service_config`
    scopes the Accessibility service. Compensated in code, not in the prompt:
    `ReplyNotificationParser` discards everything that isn't `com.whatsapp` /
    `com.whatsapp.w4b` first, and **no message body is ever stored, logged or
    exported** — only a reply count, a timestamp, and the matched keyword for
    an opt-out. The setup step says all of this in plain words.
  - **It is optional and stays optional.** `SetupUiState.isReady` deliberately
    does not require it; campaigns run without it and opt-outs are then marked
    by hand, as before.
  - **Notifications are lossy.** A reply read while the user has WhatsApp open,
    or on a phone whose notifications for that chat are muted, is never posted
    and never seen. This is a real hole with no fix at this layer.
  - **Attribution is name-based about half the time.** A WhatsApp notification
    carries a number only for *unsaved* senders; for saved ones it carries
    whatever the address book calls them. `ReplyRouter` matches by number when
    it can, by exact name when it can't, and ignores anything ambiguous rather
    than opting out the wrong person.
  - **Group messages are parsed so they can be recognised and ignored**, not so
    they can be acted on: a group notification has no number to attribute, and
    "acha" in a group of 700 is not an unsubscribe.
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

Raised by Phase 5 (2026-07-29), **still open**:
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
