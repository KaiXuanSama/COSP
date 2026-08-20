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
6. Update current-baseline detection when the new migration changes required tables, columns, indexes, or constraints.
7. Keep blocking JDBC migration work inside the runner's transaction handling. Preserve idempotence with existence checks or SQLite `IF EXISTS` / `IF NOT EXISTS` where appropriate.

## Required Test Updates

For every new version `Vnext`, update `SchemaMigrationRunnerTests`:

1. **Previous-version special case**: construct the real structural precondition for the immediately previous version, run the runner, and assert the new version's precise table/column/index/data behavior. Run it twice and verify idempotence.
2. **Recursive chain**: do not add a new hardcoded final-version test. Ensure the test iterates `registeredMigrationVersions()` and calls `migrateThrough(version)` for each checkpoint. Add checkpoint assertions when the new version changes a durable invariant.
3. **Empty database path**: run the actual classpath `schema.sql` against a fresh temporary SQLite file, then run the migration runner. Update assertions for tables and structures added or removed by `Vnext`.
4. **Dynamic version assertions**: use `SchemaMigrationRunner.currentSchemaVersion()` for current-baseline assertions. Literal version numbers are allowed only when modeling a historical source database, such as `V8.2 -> V8.3`.
5. **Data migration cases**: add focused fixtures for conflicts, invalid legacy values, data conversion, data preservation, table rebuilds, foreign keys, or indexes whenever the new migration can affect them.

## Version Boundary Rules

- V1-V6 use historical multi-row `schema_version` tracking.
- V7 compresses history to the single-row baseline `schema_version(id=1)`.
- V7.1 and later advance that single row in order.
- A new migration must work for a database arriving through the recursive history path and for a direct predecessor fixture.
- A fresh database must reach the current baseline without replaying history.

## Two Traps Already Hit

**Historical migrations must reference frozen version constants.** A migration body that writes
`CURRENT_SCHEMA_VERSION` will silently jump the version to the newest value once that constant is
bumped, so every migration in between never runs and nothing reports an error. Introduce
`V8_6_VERSION`-style constants and leave only the newest migration using `CURRENT_SCHEMA_VERSION`.

**Baseline detection needs a structural predicate, not a data probe.** Every predicate in
`isCurrentBaseline()` asks whether a table/column/index exists, because those hold on an empty
database too. A migration that only reshapes JSON inside a column has no such evidence — the only
check available would be "pick a row and look at it", which is false on an empty or brand-new
database, so the migration re-runs on every startup. V8.7 therefore added the otherwise unnecessary
`provider_request_transform.body_rules_schema` column purely to make the change a decidable
structural fact.

## Verification

1. Use editor diagnostics on the runner, schema SQL, and migration tests.
2. Run `SchemaMigrationRunnerTests` first.
3. Run focused repository tests if a table or constraint is used by a repository.
4. Run `./mvnw test`; this also validates the integrated frontend build.
5. Do not start the application, call live endpoints, or access `admin.db`.

## Checklist

- [ ] New migration version is strictly greater than the previous version.
- [ ] Existing migrations were not rewritten, and each historical body writes its own frozen version constant.
- [ ] Migration registry contains the new ordered step.
- [ ] `schema.sql` matches the new final schema.
- [ ] Baseline detection reflects the new invariant via a structural predicate.
- [ ] Previous-version upgrade test covers the new migration and idempotence.
- [ ] Recursive checkpoint chain still covers every registered version.
- [ ] Fresh SQLite plus real `schema.sql` reaches the dynamic current baseline.
- [ ] Full Maven tests pass.