# MEMORY.md

Running project state. Update this every session — see `CLAUDE.md`'s memory
discipline section. This is not a changelog (that's `CHANGELOG.md`); it's
"what's true right now and why."

## Current phase

**Every phase is now code complete and integrated.** Statuses as of 2026-07-29;
phases 1–7 were built the same day across parallel sessions, which is why so
many rows say "code complete, not device-verified". The branch
`integration-1.0.0` merges all of them into one tree for 1.0.0 — that is the
single source of truth for what the app contains, not any individual phase
branch.

| Phase | State |
| --- | --- |
| 0 — skeleton, CI, governance | **Done**, on `main` |
| 1 — Accessibility service | Code complete, merged to `features`. **Not device-verified.** |
| 2 — Contacts | **Done**, merged to `features` (PR #3) |
| 3 — Templates | Code complete, **emulator-verified**. PR #2, in `integration-1.0.0` |
| 4 — Group extractor | Code complete, screen emulator-verified. PR #5, in `integration-1.0.0`. **WhatsApp side not device-verified.** |
| 5 — Bulk sender | Code complete, merged to `features` (PR #6). **Not device-verified.** |
| 5 follow-up — reply listener | Code complete. PR #11, in `integration-1.0.0`. **Not device-verified** — the notification heuristics are the least-verified code in the repo. |
| 6 — Activity log / reports | Code complete. PR #8, in `integration-1.0.0`. **Not device-verified**; CI was its first compile. |
| 7 — Group adder | Code complete. PR #9, in `integration-1.0.0`. **Not device-verified.** |
| Pre-release hardening | Code complete. PR #10, in `integration-1.0.0` — schema export on, destructive fallback gone, version 1.0.0, R8 off. |
| Integration for 1.0.0 | `integration-1.0.0` → PR into `features`. Room at **version 2** with a real `MIGRATION_1_2`; bottom bar restructured to five tabs + More. |

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

### Phase 7 specifics

Eligibility screening, pacing/planner, the accessibility routine, the
foreground service and both screens are built. Its BUILD-PLAN acceptance
criterion — "a manual device test against a disposable test group with test
numbers only" — is **not** met, and must never be run against the client's real
groups without his explicit consent.

The group-add selectors in `WaSelectors.kt` are the *least* verified in the
file. The flow is six screens deep (chat list → search → group → group info →
add participants → confirm) versus the send path's one, so it has six times the
surface for a wrong id. Expect to correct several from a Diagnostics dump.

`WaGroupAdder` types into WhatsApp's search fields with `ACTION_SET_TEXT`.
`WaSender` deliberately avoids that by prefilling through `wa.me`; there is no
equivalent URL for "open group X's add-participants screen", so there was no
choice. It is the most fragile thing in the phase.

## Release readiness (1.0.0)

Version is `versionCode = 1` / `versionName = "1.0.0"` in `app/build.gradle.kts`.
The pre-release hardening (schema export, no destructive fallback, minification
decision) is done. **What still blocks an actually-signed APK is human work, not
code:**

1. **The four signing secrets are not set on the repository** —
   `SCOPE_WA_KEYSTORE_BASE64`, `SCOPE_WA_KEYSTORE_PASSWORD`, `SCOPE_WA_KEY_ALIAS`,
   `SCOPE_WA_KEY_PASSWORD`. Until they are, `release.yml` builds an **unsigned**
   APK and skips publishing entirely — it does not fail, it just quietly produces
   nothing installable. Generate the keystore, back it up somewhere that is not
   this repo (losing it means no future update can ever install over 1.0.0), and
   add the secrets.
2. **`app/schemas/` is generated by CI, not committed by hand.** Download the
   `room-schema` artifact from a green CI run and commit the JSON.
3. **The handset test above has still never been run.** Shipping 1.0.0 before it
   passes ships selectors nobody has confirmed exist.
4. **`update.json` and the uploaded APK disagree on filename.** `release.yml`
   writes `"apkUrl": ".../scope-wa.apk"` but uploads whatever AGP produced, which
   is `app-release.apk`. The in-app updater will 404 on the first update check.
   Either rename the APK in the workflow or fix the URL — left alone here
   deliberately, since it is the release workflow's bug to fix, not this change's.
5. **Minification is off for 1.0.0**, deliberately — see the comment on
   `release { }` in `app/build.gradle.kts`. CI has never built an R8 APK and
   nobody can test one on a device yet. `proguard-rules.pro` is kept accurate so
   re-enabling is a two-line change plus a device test.

## Key decisions on record

- **Group-add eligibility is enforced in code and derived from the contact
  row, never entered by hand.** A contact is addable only if
  `times_replied > 0` (they messaged first) or `source_group` is set (Phase 4
  extracted them from a group they already belong to). Everything else is
  `Cold` and refused, and `GroupAddPlanner` re-screens its own input so a
  caller that forgets to filter still can't add cold numbers. Architecture doc
  section 6 layer 5 — the rule the client's browser extension doesn't have.
- **The "needs invite link" bucket is terminal, not a retry queue.** A number
  blocked by its owner's group-privacy setting leaves the queue permanently
  (`GroupAddOutcome.isRetryable == false`); the UI offers the list for sharing
  so the client sends a link by hand. Architecture doc section 8 calls this
  "not a bug", and treating it as a failure to retry would burn the daily cap
  on people who can never be added.
- **A privacy block and a skip are neither of them failures.** Only
  `GroupAddBucket.Failed` outcomes feed the two-consecutive-failure breaker.
  Same reasoning as the send side, but it bites harder here because the
  group-add breaker is two deep instead of three.
- **Group-add pacing constants live in `brain/groupadd/GroupAddPacing`, not in
  `brain/pacing/`.** `brain/pacing/` holds the Safe/Normal/Fast *profiles* the
  user chooses between; layer 5's numbers are not a profile and there is
  deliberately no picker for them. `GroupAddPacingTest` asserts the literals
  (3, 60, 150, 8, 15, 20, 2) rather than reading the constants back, so
  loosening one fails CI instead of quietly changing the product.
- **Phase 7 added no `@Database(entities = [...])` entry.** There is no
  `group_add_targets` table; per-person results live on the `group_add_jobs`
  row as encoded `List<String>` / `Map<String, String>` columns. Acceptable
  only because the daily cap of 20 caps a job at a few dozen people — do not
  copy this shape for anything unbounded.
- **`GroupAddJobService` is a separate service from `CampaignJobService`, not a
  mode of it.** They share a shape but almost no rules; folding them together
  would mean a flag on each difference, and the flag that gets set wrong is the
  one that adds 20 cold numbers to a group.
- **`WaServiceBridge.pressBack()` was added by Phase 7.** The group-add flow has
  no deep link to re-enter on, so backing out is the only way to recover from a
  half-finished add without stranding WhatsApp on a dialog — which would doom
  the next person in the batch, and the breaker is only two deep.

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
- **Room schema export is on and destructive migration is gone — done for the
  1.0.0 release.** `exportSchema = true`, the `room.schemaLocation` KSP arg
  points at `app/schemas/` (tracked in git, and wired into the `androidTest`
  assets so `MigrationTestHelper` can read it), and
  `fallbackToDestructiveMigration()` has been removed from the builder. The
  window where a wipe-and-recreate was free is closed.
  **Every schema change from here needs a real `Migration` object plus a
  `version` bump, with the new `app/schemas/<n>.json` committed alongside it.**
  Without destructive fallback, a missing migration is an `IllegalStateException`
  on the user's first launch after updating, not a silent reset.
- **1.0.0 ships at schema version 2, with a hand-written `MIGRATION_1_2`**
  (`data/db/migration/Migrations.kt`), registered via `.addMigrations(...)`.
  Version 1's schema JSON was exported by CI *before* Phase 4, Phase 7 and the
  reply listener merged, so the 1 → 2 gap is wider than the reply columns alone:
  it also covers `extractions.reported_member_count` / `imported_count` and the
  eighteen columns plus two indices Phase 7 added to `group_add_jobs`. A
  migration written for only the reply columns would pass review and then throw
  on a device, because Room validates the *whole* post-migration schema against
  the entity hash. `1.json` stays committed — it is the starting schema any
  future `MigrationTestHelper` test has to migrate from.
- **Nobody on this project has a local JDK, so CI generates the schema JSON.**
  `ci.yml` fails if KSP exported nothing, warns if `app/schemas` differs from
  what's committed, and uploads a `room-schema` artifact to download and commit.
  Never hand-write one.
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
- **Phase 6's report logic lives in `data/repository/report/`, not `brain/`.**
  Same precedent as Phase 2's contact parsers: it belongs to one feature, so it
  sits in that feature's directory with zero Android imports, and CI unit tests
  it without a phone. `brain/` stays the cross-cutting anti-ban logic.
- **A report's success rate is `sent / (sent + failed)` — skips are excluded
  from the denominator entirely.** This is "a skip is never a failure" carried
  through to the number the client actually reads. Skips get their own counted,
  categorised block and an amber chip rather than a red one, because a report
  that scored a correct opt-out skip as a miss would push the client toward
  turning the safety layers off. A campaign where everything was skipped reports
  0 attempted and 0%, not a divide-by-zero.
- **Skip reasons are classified out of free text, not stored as an enum.**
  `campaign_messages.error` holds the sentence the Running screen shows
  ("Messaged in the last 30 days"); `SkipCategory.classify` keyword-matches it
  back into buckets. If `CampaignRepository.describeSkip`'s wording ever
  changes, update the classifier's keywords with it — there is a unit test
  pinning the current sentences.
- **The activity log excludes `Pending` rows.** The log answers "what
  happened"; a queued message has not happened yet, and the Running screen is
  where the live queue belongs. It also orders by `COALESCE(sent_at, 0) DESC,
  id DESC` because a skipped message never gets a `sent_at`.
- **CSV escaping is single-sourced in `data/repository/csv/CsvWriter`.**
  Extracted from `ContactExporter` by Phase 6; a rendered WhatsApp message
  routinely contains commas, quotes and newlines, and a second escaper would
  have been a second chance to silently shift every column.
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

Raised by Phase 6 (2026-07-29), not blocking:
- **The report has no reply or response rate**, because nothing reads incoming
  WhatsApp messages (see the Phase 5 gap above). "How many people answered" is
  the figure a client naturally wants off a campaign report, so this gap is now
  visible in the product, not just in docs. It lands for free once replies are
  observed.
- **Nothing prunes `campaign_messages`.** The log is every message ever, which
  is exactly what makes it a receipt — but at 5,000/day it is ~1.8M rows a year.
  The query is indexed and paged, so this is a housekeeping question ("delete
  campaigns older than N months"?) rather than a correctness one. Ask the client
  before inventing a retention policy.

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
  green" as no evidence at all about a regex literal: nothing in the JVM test
  suite can catch this class of bug — only running the APK can.
- **`Scaffold` does not inset a custom `bottomBar`.** With `enableEdgeToEdge()`,
  a screen-level bottom bar draws underneath the system navigation bar unless
  something insets it. But `MainActivity`'s outer `Scaffold` + bottom nav
  already does that for every screen in the `NavHost`, so a nested screen must
  *not* add `navigationBarsPadding()` as well — that leaves a dead gap. Checked
  both ways on a device.
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
- **You can still run the app locally, and you should.** `emulator` and `adb`
  need no JDK, and there is an AVD called `scope_test` (API 30). Download the
  debug APK from a green CI run (`gh run download <id> -n scope-wa-debug`) and
  install it. Phase 3 found a crash this way that CI is structurally unable to
  catch — see the regex note above. Gotchas: Git Bash rewrites device paths, so
  `adb shell uiautomator dump /sdcard/ui.xml` needs `MSYS_NO_PATHCONV=1`, but
  that same variable breaks local paths for `adb install`, which then need
  `cygpath -w`. Driving the UI by `uiautomator dump` and tapping node bounds is
  far more reliable than fixed-delay `input tap` on a software-rendered
  emulator, and the soft keyboard silently eats swipes until it is dismissed.
- **The emulator is a shared resource.** Parallel phase sessions install over
  each other's builds — same `com.tricreta.scopewa.debug` package. Reinstall
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
