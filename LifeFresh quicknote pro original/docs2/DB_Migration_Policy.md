# Database Migration Policy — LifeFresh QuickNote Pro

**Date:** 15 September 2026
**Applies to:** `app/src/main/java/com/example/data/database/AppDatabase.kt` and any future Room schema change.

## Current state

| Item | Value |
|---|---|
| Database name | `lifefresh_database` |
| Current schema version | **13** |
| Migration chain | 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12 → 13 (all defined in `AppDatabase.kt`) |
| Migration tests | `Migration7To8Test`, `Migration8To9Test` (pattern to follow) |
| Destructive fallback | **Removed** (Phase 0, 2026-09-15). Room will now fail loudly instead of silently wiping data when a version has no migration path. |

## Rules (non-negotiable)

1. **Never use destructive migrations.** `fallbackToDestructiveMigration()` is banned.
   If an on-disk version has no path forward, add the missing `Migration` — do not
   re-enable the fallback. Silently erasing a business owner's lead database is a
   product-destroying defect.
2. **One batched version bump for a feature wave.** All new tables/columns for a
   Phase (e.g. Phase 2/3: `ai_audit_logs`, pending-confirmation persistence,
   feature flags) go into a **single** `MIGRATION_13_14`. Never bump the version
   per-table — each extra version multiplies the migration-chain risk.
3. **Every migration gets a test.** Copy the `Migration7To8Test` /
   `Migration8To9Test` pattern: build a database at version N with representative
   data, run the migration to N+1, assert the data survived. A migration without a
   test is not merged.
4. **Migrations are data-preserving and idempotent in effect.** Additive DDL only
   (`CREATE TABLE`, `ALTER TABLE ADD COLUMN` with defaults). No `DROP` of user
   data, no row rewrites that lose information.
5. **No production downgrades.** The app never migrates downward. If a downgrade
   is ever genuinely needed, it is a new, explicitly-reviewed migration.
6. **Backup before the app first touches a new version.** For the v13→v14 wave,
   the existing JSON backup/restore flow must be verified to work *before* the
   migration ships, so a user can recover if anything goes wrong.
7. **Schema export (recommendation).** `exportSchema` is currently `false`. When
   doing the v14 wave, enable it and commit the JSON schemas under
   `app/schemas/` so future migrations can be reviewed against the real shapes.

## Version plan (planned, not yet built)

| Version | Contents | Phase |
|---|---|---|
| 14 | `ai_audit_logs` (append-only audit chain), pending-confirmation persistence table, feature-flag table | Phase 2–3 |

Until then, version **13** remains the only supported target above 12.

## If a "no migration path" crash is ever reported

1. Identify the on-disk version from the log (`RoomDatabase` exception names it).
2. Add `MIGRATION_X_13` (or a chain) covering the gap.
3. Add its test (rule 3).
4. Ship. Do not ship a destructive fallback.
