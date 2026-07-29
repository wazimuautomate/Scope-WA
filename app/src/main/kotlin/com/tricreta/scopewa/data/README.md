# data/

Room database package. Everything is local to the phone; nothing is uploaded
anywhere (architecture doc section 5.3).

## Layout

- `db/entity/` — one file per table. `TemplateEntity` is real (Phase 3);
  `Shells.kt` holds placeholder entities for the other seven tables.
- `db/dao/` — one DAO per entity that has one. Only `TemplateDao` exists so far.
- `db/ScopeWaDatabase.kt` — the `RoomDatabase` subclass and its singleton.
- `repository/` — one repository per feature area. UI and job-runner code talk
  to repositories, never to DAOs.

## Why all eight tables are declared already

`docs/BUILD-PLAN.md` ("Shared hotspots") makes `ScopeWaDatabase.kt` a
coordination point: if each phase added its own `@Database(entities = [...])`
entry, every parallel phase branch would conflict on one line. The rule there is
that whichever phase lands the database first scaffolds all eight tables from
architecture doc section 5.3, and later phases only add **columns and DAOs to
their own entity**.

Phase 3 landed first — before Phase 2, which the build plan expected to get here
— so the shells in `db/entity/Shells.kt` are Phase 3's stand-ins for other
phases' tables. Filling one in is an additive change to that entity file; it does
not require touching `ScopeWaDatabase.kt`.

## Migrations

The database is still built with `fallbackToDestructiveMigration()`. That is
deliberate while the shells are being filled in and there is no real client data
on any device, and it **must be replaced with real migrations before the first
APK ships to the client**.
