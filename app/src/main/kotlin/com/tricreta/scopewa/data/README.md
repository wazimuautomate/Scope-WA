# data/

Room database and repositories. Everything stays on the phone — architecture
doc section 5.3.

## Layout

- `db/entity/` — one file per table. All eight tables from architecture doc
  section 5.3 are registered, plus two the design needs but the prose summary
  doesn't name:
  - `contact_list_members` — the many-to-many join between contacts and lists.
  - `suppression_list` — number-level blocks that survive a contact row being
    deleted and re-imported, which is exactly when a resurrected STOP reply
    would do real damage.
- `db/dao/` — `ContactDao`, `ContactListDao`, `SuppressionDao`. Later phases add
  their own DAO next to these.
- `db/ScopeWaDatabase.kt` — the `RoomDatabase`. **Add fields and DAOs, not new
  `entities = [...]` entries** (see `docs/BUILD-PLAN.md`, shared hotspots).
- `db/Converters.kt` + `db/StringCodec.kt` — `List<String>` and
  `Map<String, String>` columns. The encoding lives in `StringCodec` so it is
  unit tested without Room.
- `repository/contacts/` — the Contacts feature. Everything that decides *what
  the data means* is pure Kotlin with no Android imports, so CI tests it
  without a phone:
  - `parse/` — `CsvParser`, `VcfParser`, `TxtParser`, `ContactFileParser`,
    ported from the proven Chrome extensions in `docs/reference/`.
  - `ContactImporter` — normalise via `brain/phone/PhoneNormalizer`, dedupe,
    then split rows into new / already-known / suppressed / unusable.
  - `ContactExporter` — CSV, TXT, VCF and JSON **file** content.
  - `ContactsRepository` — the only thing the UI and job runner talk to.

## Two rules worth repeating

1. **Export produces files, never phonebook entries.** Per the client's answer
   in architecture doc section 10 Q8, nothing here may be routed into Android's
   contacts provider — including the VCF exporter, tempting as it looks.
2. **Suppressed numbers are dropped on import, not imported and flagged.**
   That's what makes a STOP reply stick across a delete-and-reimport.

## Schema versioning

`exportSchema = false` with destructive fallback is deliberate *while
unreleased* — nothing has shipped, so there's no user data to migrate and a
committed schema would only record guesses that later phases will change.
Before the first signed release: turn schema export on, add the
`room.schemaLocation` KSP arg, commit the schema, and drop the destructive
fallback. Tracked in `MEMORY.md`.
