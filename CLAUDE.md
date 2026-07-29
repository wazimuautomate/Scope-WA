# CLAUDE.md

Instructions for any Claude Code session (or other AI assistant) working in
this repository.

**Repository:** https://github.com/wazimuautomate/Scope-WA (public — app repos live under `wazimuautomate`; `TricretA` is personal/website projects)

## What this project is

An Android app (`com.tricreta.scopewa`) that drives the WhatsApp already
installed on a client's phone via Android's Accessibility Service, to bulk
send messages, extract WhatsApp group contacts, and bulk-add people to
groups — wrapped in a five-layer anti-ban system. Full design:
[`docs/ARCHITECTURE-V2-WHATSAPP.md`](docs/ARCHITECTURE-V2-WHATSAPP.md).

The build is broken into phases so multiple sessions can work in parallel:
[`docs/BUILD-PLAN.md`](docs/BUILD-PLAN.md). **Read that before starting any
implementation work** — it tells you which phase to pick up, what files you
own, what's already built, and what depends on what.

## Branch policy — enforced, not optional

- `main` — always releasable. The release workflow (`.github/workflows/release.yml`)
  builds a signed APK off `main`.
- `features` — integration branch. All phase work merges here first.
- Per-phase branches (e.g. `phase-2-contacts`) — branch off `features`, never off `main`.

**Every change goes: `phase branch` → PR into `features` → (once stable)
PR from `features` into `main`.** Nobody pushes directly to `main` except
the repository owner doing a deliberate release merge. Nobody pushes
directly to `features` either — even small fixes go through a branch and a
PR, so CI runs before anything lands.

The one exception: the initial scaffold commit, made directly to `main` by
the owner during project setup, before this policy existed to enforce.
Everything after that follows the rule above.

Before opening any PR:
- CI (`ci.yml`) must be green — unit tests + debug build.
- **The phase has been run on the emulator** (see below) with screenshots of
  what you built. "It compiles" is not evidence that it works.
- `CHANGELOG.md` has a new entry under `[Unreleased]` describing what changed and why.
- `MEMORY.md` reflects the new state (phase status, open questions, decisions made).

## Emulator testing — required at the end of every phase

There is a working Android emulator on this machine. **Every phase must be
run on it before its PR is opened**, and the PR must say what was observed,
with screenshots. Compiling is not the same as working — Phase 1 shipped a
readiness check that CI was perfectly happy with and that only the emulator
could actually exercise.

```
SDK       C:\Users\ADMIN\AppData\Local\Android\Sdk
adb       %SDK%\platform-tools\adb.exe
emulator  %SDK%\emulator\emulator.exe
AVD       scope_test   (Android 11, API 30, x86_64)
```

There is **no local JDK**, so you cannot build locally. The loop is:

```powershell
$adb = "C:\Users\ADMIN\AppData\Local\Android\Sdk\platform-tools\adb.exe"
# 1. push your branch, let CI build it, then grab the APK it produced
$run = gh run list --repo wazimuautomate/Scope-WA --branch <your-branch> `
        --workflow CI --status success --limit 1 --json databaseId --jq '.[0].databaseId'
gh run download $run --repo wazimuautomate/Scope-WA --dir <dir>
# 2. install (uninstall first — CI's debug keystore differs from any local one)
& $adb uninstall com.tricreta.scopewa.debug
& $adb install -r <dir>\scope-wa-debug\app-debug.apk
# 3. drive it
& $adb shell am start -n com.tricreta.scopewa.debug/com.tricreta.scopewa.MainActivity
& $adb exec-out screencap -p > shot.png
```

Note the debug build's application id is `com.tricreta.scopewa.debug`
(`applicationIdSuffix`), while class names keep the `com.tricreta.scopewa`
package — so the accessibility service component is
`com.tricreta.scopewa.debug/com.tricreta.scopewa.accessibility.WaAccessibilityService`.

Enabling the accessibility service without tapping through Settings:

```powershell
& $adb shell settings put secure enabled_accessibility_services `
    com.tricreta.scopewa.debug/com.tricreta.scopewa.accessibility.WaAccessibilityService
& $adb shell settings put secure accessibility_enabled 1
& $adb logcat -d -s WaAccessibility:*      # expect "Accessibility service connected"
```

### Two limits to be honest about

1. **WhatsApp is not installed on the emulator and realistically cannot be.**
   Registration needs a real number and an SMS code. So the emulator verifies
   *our* app — screens, navigation, database, the service binding, readiness
   logic — but **cannot verify any actual WhatsApp automation.** Anything
   under `accessibility/` that drives WhatsApp still needs the client's real
   handset. Do not claim a WhatsApp interaction works because the emulator was
   happy; say exactly which half you exercised.
2. **The emulator is a shared, single instance.** When several phase sessions
   run at once they fight over it — one session's `uiautomator` polling or
   `am force-stop` will reset another's accessibility settings and kill its
   app mid-test, which reads as a phantom bug. Before trusting a surprising
   result, check whether someone else is driving the device:
   `& $adb logcat -d | Select-String uiautomator`. If so, either wait or
   create a second AVD.

## Memory and changelog discipline

This repo tracks its own history in two files, separate from `git log`:

- **`MEMORY.md`** — running project state: which phase is active, what's
  decided, what's still open, what broke and why. Update it *every session*,
  not just at the end of a phase. Treat stale entries as bugs — remove or
  correct them rather than letting them accumulate.
- **`CHANGELOG.md`** — one entry per merged PR, under `[Unreleased]` until a
  release cuts a version. This is the human-readable history of what
  shipped; `git log` is the mechanical one.

Every session that writes code must update both before considering the work
done, not just when explicitly asked.

## Working rules specific to this codebase

- **`brain/` has zero Android imports.** If you're tempted to import
  anything from `android.*` there, the code belongs in `jobrunner/` or
  `accessibility/` instead. This is what makes the anti-ban logic unit
  testable in CI without a phone — don't break it.
- **The pacing numbers (delays, caps, warm-up ramp) in `brain/pacing/` are
  the product, not tuning knobs to loosen for convenience.** Any change to
  them needs a reason tied back to `docs/ARCHITECTURE-V2-WHATSAPP.md`
  section 6, not just "campaigns feel slow."
- **Never write extracted WhatsApp contacts to the Android contacts
  provider.** Per the client's explicit answer (architecture doc section 10,
  Q8), extraction is export-only (CSV/VCF/etc. as files) — nothing touches
  the phonebook.
- **All WhatsApp-specific selectors live in `accessibility/WaSelectors.kt`.**
  Never hardcode a view-id or content-description anywhere else — see
  architecture doc section 8 for why (WhatsApp changes its UI regularly).
- **Distribution is a signed APK via GitHub Releases, not the Play Store.**
  Don't add Play Store publishing config; it was rejected as a strategy for
  the reasons in architecture doc section 4.
- Real device testing (anything under `accessibility/`) can't be fully
  verified by CI **or by the emulator**, which has no WhatsApp. Say explicitly
  in a PR what was tested on the emulator, what needs the client's handset,
  and what was only compiled.
