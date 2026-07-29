# MEMORY.md

Running project state. Update this every session — see `CLAUDE.md`'s memory
discipline section. This is not a changelog (that's `CHANGELOG.md`); it's
"what's true right now and why."

## Current phase

**Phase 0 — done.** Project skeleton, CI, git repo, and governance docs.

**Phase 2 (Contacts) — built on `phase-2-contacts`, PR open into `features`
(2026-07-29).** Room schema (all ten tables), CSV/VCF/TXT import with dedupe
and a confirm-before-you-write preview, lists + bulk-select picker,
CSV/TXT/VCF/JSON file export, and the `opted_out` + suppression plumbing Phase 5
needs for STOP handling. Verified by CI only — compile plus unit tests. No
device test was done and none is needed: Phase 2 touches no Accessibility code.

**Phases 1 and 3 ran in parallel in other sessions** (`phase-3-templates` has
its own worktree; a session was also editing `accessibility/` and
`brain/whatsapp/`, i.e. Phase 1). Phase 2 no longer blocks anything.

Next up: Phase 4 (extractor) unblocks once Phase 1 lands; Phase 5 needs 1, 2
and 3. See `docs/BUILD-PLAN.md`.

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

## Known risks to keep front of mind

- Ban risk is reduced, not eliminated, at any volume — this must stay
  visible in the product (see architecture doc section 2), not just in docs.
- WhatsApp UI changes are a "when," not "if" — that's what
  `accessibility/WaSelectors.kt` centralizing selectors is for.
- WhatsApp's LID rollout hides some group members' numbers; how many is
  per-group and unknowable until Phase 4 extraction runs against the
  client's actual groups.

## Environment notes

- No local JDK/Gradle/Android SDK detected on this machine as of 2026-07-29
  — all builds currently go through GitHub Actions CI. If a future session
  finds local tooling installed, this note is stale; remove it.
- `gh` CLI has multiple accounts authenticated locally (`TricretA`,
  `wazimuautomate`, `Wazimu90`); active account must be `wazimuautomate` for
  this repo (`gh auth switch --hostname github.com --user wazimuautomate`).
- **Parallel sessions share one checkout — use `git worktree`.** Two sessions
  running in `C:\Users\ADMIN\OneDrive\Desktop\Scope WA` at once will fight over
  the branch and each other's uncommitted files (this happened on 2026-07-29:
  a `git checkout -b` moved the branch out from under another session's
  in-progress edits). Phase 2 and Phase 3 each ran from
  `git worktree add <dir> -b phase-N-<name> origin/features`. Do that.
- `ci.yml` runs on pushes to `main`/`features`, PRs into either, and
  `workflow_dispatch`. A push to a `phase-*` branch alone does **not** trigger
  it — run `gh workflow run ci.yml --ref <branch>` to check a phase branch
  before opening its PR.
