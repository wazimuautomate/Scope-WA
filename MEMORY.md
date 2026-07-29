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

### Phase 3 decisions Phase 2 and Phase 5 need to know

- **Phase 3 landed the Room database, not Phase 2.** `docs/BUILD-PLAN.md`
  recommended Phase 2 do it, but its rule is "whoever lands first scaffolds all
  eight tables" — so `data/db/entity/Shells.kt` holds one-line placeholder
  entities for the seven tables Phase 3 doesn't own. Filling one in is an
  additive edit to that entity file; nobody needs to touch
  `data/db/ScopeWaDatabase.kt`'s `@Database(entities = [...])` list again.
- **`fallbackToDestructiveMigration()` is deliberate and temporary.** Fine while
  shells are being filled in and no client data exists on any device; it must
  become real migrations before the first APK ships.
- **`TemplateEntity.knownVariables` is load-bearing, not metadata.**
  `TemplateEngine` decides `{name|there}` means "CSV value, or *there* if blank"
  — rather than a coin flip between two words — by looking `name` up in that
  set. Phase 5 must pass the stored list to the engine at send time, or
  templates render differently than they previewed.
- **The editor's uniqueness meter is a floor, not a prediction.** It holds CSV
  values constant and measures spintax variation only, because there is no
  contact list until Phase 2. Phase 5 must recompute it against the real
  rendered campaign before sending — that is the number section 6 describes.
- **`ui/home/HomeScreen.kt` has a "Templates" button** next to Phase 1's Setup
  and Diagnostics buttons. Templates need no Accessibility permission, so the
  screen is reachable before setup is finished.

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

Phases 2 (Contacts) and 3 (Templates) are being built in parallel by other
sessions and depend on none of this.

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

## Things learned while building (don't rediscover these)

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
- `gh` CLI has multiple accounts authenticated locally (`TricretA`,
  `wazimuautomate`, `Wazimu90`); active account must be `wazimuautomate` for
  this repo (`gh auth switch --hostname github.com --user wazimuautomate`).
