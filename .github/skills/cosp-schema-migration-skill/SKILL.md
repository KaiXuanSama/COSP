---
name: cosp-schema-migration-skill
description: "Implement COSP SQLite schema changes and migrations. Use when: adding or changing schema versions, SchemaMigrationRunner, schema.sql, database tables, columns, indexes, constraints, or migration tests."
---

# COSP Schema Migration Workflow

## Scope

Use this workflow for every persistent SQLite schema change. COSP maintains a current schema baseline in
`src/main/resources/schema.sql` and ordered incremental upgrades in
`SchemaMigrationRunner`. Do not edit, delete, or inspect the workspace `admin.db`.

The migration test contract has three layers:

```text
Historical legacy schema -> recursive migration checkpoints -> current schema
Empty SQLite file -> real schema.sql initialization -> current baseline
Previous version fixture -> new migration -> targeted data/structure assertions
```

## Required Implementation Steps

1. Inspect `SchemaMigrationRunner` and its ordered migration registry before changing schema.
2. Add the next monotonic version only. Never rewrite or delete a historical migration.
3. Add its `MigrationStep` to the ordered registry with a precise description and transactional action.
4. Update `CURRENT_SCHEMA_VERSION`; new code must not hardcode the old current version in tests.
5. Update `schema.sql` to describe only the new final schema. It must not recreate obsolete tables, columns, indexes, triggers, or data.
6. Leave `isCurrentBaseline()` alone. It compares the recorded version only; no structural predicate belongs there.
7. Keep blocking JDBC migration work inside the runner's transaction handling. Preserve idempotence with existence checks or SQLite `IF EXISTS` / `IF NOT EXISTS` where appropriate.

## Required Test Updates

For every new version `Vnext`, update `SchemaMigrationRunnerTests`:

1. **Previous-version special case**: construct the real structural precondition for the immediately previous version, run the runner, and assert the new version's precise table/column/index/data behavior. Run it twice and verify idempotence.
2. **Recursive chain**: do not add a new hardcoded final-version test. Ensure the test iterates `registeredMigrationVersions()` and calls `migrateThrough(version)` for each checkpoint. Add checkpoint assertions when the new version changes a durable invariant.
3. **Empty database path**: run the actual classpath `schema.sql` against a fresh temporary SQLite file, then run the migration runner. Update assertions for tables and structures added or removed by `Vnext`.
4. **Dynamic version assertions**: use `SchemaMigrationRunner.currentSchemaVersion()` for current-baseline assertions. Literal version numbers are allowed only when modeling a historical source database, such as `V8.2 -> V8.3`.
5. **Data migration cases**: add focused fixtures for conflicts, invalid legacy values, data conversion, data preservation, table rebuilds, foreign keys, or indexes whenever the new migration can affect them.
6. **No-replay guard**: extend `upgradeFromPreviousVersionRunsOnlyTheMissingMigration` so the new version appears in the log and every earlier version does not. Match on `"已应用 V9:"` including the colon — without it, `已应用 V9` is a prefix of `已应用 V9.x` and the assertion can never fail.

## Version Numbering

The next version is the **integer `V9`**, then V10, V11, and so on. The `a` in `a.b` never carried
any logic, and `V8.10` as a double *is* `8.1`, which collides with the registered V8.1 and gets
silently skipped as "already applied". Historical V8.1-V8.9 must stay in the registry verbatim for
old databases, so the version type remains `double`; only new versions take integral values.

All version comparison goes through `VERSION_COMPARISON_EPSILON`. The historical `a.b` values are
inexact as doubles, so `==` only works when both sides come from the same literal.

`formatVersion()` renders integral values without the decimal part, so V9 logs as `V9`, not `V9.0`.

## Version Boundary Rules

- V1-V6 use historical multi-row `schema_version` tracking, where row equality is the applied check.
- V7 compresses history to the single-row baseline `schema_version(id=1)`.
- V7.1 and later advance that single row in order, and the applied check becomes a **version
  comparison**. Never use row equality on the single-row table: a database sitting at 8.9 has no
  `version = 8.5` row even though V8.5 ran long ago.
- A database behind the current version runs **only the missing migrations**. `baselineMigrations()`
  lower-bounds by the recorded version; `migrate()` re-checks via `isAppliedOnBaseline()`. Both
  layers must stay — "which migrations are needed" is a version-range question, not something to
  leave to a runtime fallback.
- A new migration must work for a database arriving through the recursive history path and for a
  direct predecessor fixture.
- A fresh database must reach the current baseline without replaying history.

## Three Traps Already Hit

**Historical migrations must reference frozen version constants.** A migration body that writes
`CURRENT_SCHEMA_VERSION` will silently jump the version to the newest value once that constant is
bumped, so every migration in between never runs and nothing reports an error. Introduce
`V8_6_VERSION`-style constants and leave only the newest migration using `CURRENT_SCHEMA_VERSION`.

**Replay is not made safe by idempotence.** The applied check was once row equality applied to both
table shapes, so a jar upgrade replayed every migration after V7.1. Idempotence only covers DDL:
V8.6's protocol backfill rewrites log rows the application layer already filled correctly, and V8's
key convergence deletes a legitimate provider whose name starts with `custom-` along with its
encrypted API keys. `SchemaMigrationRunnerTests` locks this with
`crossVersionUpgradeKeeps*` plus `upgradeFromPreviousVersionRunsOnlyTheMissingMigration`.

**Do not invent a structural marker column to make a data-only migration "decidable".** This used to
be required: `isCurrentBaseline()` demanded structural evidence, and failing it meant a full replay,
so V8.7 and V8.9 each added a schema-version column (`body_rules_schema`,
`reasoning_effort_schema`) purely to have something to check. That reason is gone. Baseline
detection now compares the recorded version only, and a failed check merely skips the shortcut —
`baselineMigrations()` then filters to an empty list anyway. Those two columns stay because shipped
schema cannot be reclaimed and they aid manual inspection; do not copy the pattern.

## Verification

1. Use editor diagnostics on the runner, schema SQL, and migration tests.
2. Run `SchemaMigrationRunnerTests` first.
3. Run focused repository tests if a table or constraint is used by a repository.
4. Run `./mvnw test`; this also validates the integrated frontend build.
5. Do not start the application, call live endpoints, or access `admin.db`.

## Checklist

- [ ] New migration version is an integer, strictly greater than the previous version.
- [ ] Existing migrations were not rewritten, and each historical body writes its own frozen version constant.
- [ ] Migration registry contains the new ordered step.
- [ ] `schema.sql` matches the new final schema.
- [ ] `isCurrentBaseline()` was not given a structural predicate, and no marker column was added just to be detectable.
- [ ] Previous-version upgrade test covers the new migration and idempotence.
- [ ] Recursive checkpoint chain still covers every registered version.
- [ ] Fresh SQLite plus real `schema.sql` reaches the dynamic current baseline.
- [ ] A database one version behind runs only the new migration and nothing earlier.
- [ ] Full Maven tests pass.