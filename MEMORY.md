# MEMORY.md

Running project state. Update this every session — see `CLAUDE.md`'s memory
discipline section. This is not a changelog (that's `CHANGELOG.md`); it's
"what's true right now and why."

## Current phase

**Phase 0 — done.** Project skeleton, CI, git repo, and governance docs.

**Phase 3 (Templates) — built, on branch `phase-3-templates`, PR into
`features`** (2026-07-29). Template list + editor, variable chips, spintax
starters, live preview cycling 5 renders, uniqueness meter in the doc's exact
wording. New pure-Kotlin `brain/template/TemplateAnalyzer.kt`,
`brain/template/TemplateVariables.kt` and
`brain/uniqueness/UniquenessSummary.kt`, all unit tested. Phase 5 consumes
these; nothing else does yet.

**Phase 4 (Group extractor) — in progress in a parallel session** as of the
same day, working in `accessibility/WaSelectors.kt`, `accessibility/WaPackage.kt`
and a new `brain/whatsapp/` package. Note it started ahead of its stated
dependencies (Phases 1 and 2).

Still unstarted: Phases 1 (Accessibility Service), 2 (Contacts), 5, 6, 7.
Phase 1 needs a physical Android device and is the long pole.

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
- **`ci.yml` now runs on PRs into `features`.** It previously ran only on PRs
  into `main`, so `CLAUDE.md`'s "CI green before merging into `features`" rule
  was unenforceable.
- **`ui/home/HomeScreen.kt` has a temporary "Templates" button.** Phase 1 owns
  that file and should drop the button when it builds the real home screen.

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
