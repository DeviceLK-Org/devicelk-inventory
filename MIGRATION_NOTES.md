# Flyway adoption — verification notes

> **SUPERSEDED IN PART — read this first.**
>
> Everything below documents the Flyway adoption of the **modular monolith**, whose
> `V1__baseline.sql` created the `inventory`, `cart` and `orders` schemas plus
> `public.event_publication` in a single `devicelk_commerce` database. That database
> and that baseline no longer exist in this form.
>
> During the commerce extraction, `cart`, `orders` and `event_publication` moved to
> **DeviceLK-Commerce**, which owns them in its own `devicelk_commerce` database with
> its own `V1__baseline.sql`. This service's baseline was rewritten to contain the
> `inventory` schema alone, and its database is now `devicelk_inventory`.
>
> **`V1__baseline.sql` was edited, against the rule stated at the bottom of this
> file.** That rule protects databases where V1 has already been applied: editing it
> there either fails checksum validation or silently describes a schema no live
> database has. Neither applied — this baseline had only ever run on one developer
> laptop and had never been pushed, so there was no deployed database whose history
> it recorded. The alternative was keeping the original V1 and shipping a V2 that
> DROPs the cart and orders schemas, which would mean every fresh inventory database
> creating cart and order tables and then destroying them, and would leave the
> baseline describing two modules this service does not contain.
>
> **The rule has resumed.** Both services' V1 files are frozen; changes ship as V2,
> V3, ….
>
> **What is still valid below:** the Flyway *mechanism* — that a fresh empty database
> gets V1 run against it, and a database that already carries the schema is adopted
> in place with a baseline row and no data loss. That behaviour is unchanged, it is
> what both services still rely on, and the runs recorded below are the evidence for
> it. Only the schema contents described are out of date.
>
> One improvement came out of the split and is worth noting here: the `./init`
> scripts no longer pre-create schemas, and the tests no longer seed them either.
> Both used to make the database non-empty before Flyway ran, which flipped
> `baseline-on-migrate` into *adopting* it — so V1 was skipped, Hibernate's
> `ddl-auto` built the tables, and the baseline went untested on every run. Both
> services now start from a genuinely empty database and let V1 build it, with
> `ddl-auto: validate` asserting the result.

Evidence that introducing Flyway (a) brings a brand-new empty Postgres up to the
**exact** current schema, and (b) adopts the existing hand-built dev DB without
re-running anything or losing data.

- **Flyway version:** 9.22.3 (managed by Spring Boot 3.2.4, pinned via the
  `flyway.version` property in `pom.xml`; on the app classpath as
  `org.flywaydb:flyway-core:jar:9.22.3:compile`).
- **Baseline source of truth:** `V1__baseline.sql`, generated from the running dev
  DB with `pg_dump --schema-only --no-owner --no-privileges` and stripped of
  owner/privilege/tablespace/psql-meta noise.
- **How these runs were driven:** the Flyway **Maven plugin pinned to 9.22.3** with
  the same three properties the app sets in `application.yml`
  (`baselineOnMigrate=true`, `baselineVersion=1`,
  `locations=…/db/migration`). This is the identical `flyway-core` engine Spring Boot
  auto-configures at startup — the plugin just lets us drive it against throwaway
  databases without booting the whole service.

Throwaway Postgres used for verification: `postgres:16` containers (matching dev),
one empty ("fresh"), one seeded with a copy of the dev schema ("existing").

---

## Run 1 — FRESH empty Postgres → Flyway runs V1 from scratch

Empty `postgres:16`, no schema, no init scripts. Ran `flyway:migrate`:

```
[INFO] Flyway Community Edition 9.22.3 by Redgate
[INFO] Successfully validated 1 migration (execution time 00:00.019s)
[INFO] Creating Schema History table "public"."flyway_schema_history" ...
[INFO] Migrating schema "public" to version "1 - baseline"
[INFO] Successfully applied 1 migration to schema "public", now at version v1
```

Flyway created the schema history table and **ran V1** (there was nothing to
baseline). Result: `inventory`, `cart`, `orders` schemas + `public.event_publication`
all created.

### Proof that fresh == dev (schema diff)

`pg_dump --schema-only --no-owner --no-privileges` of the fresh DB (excluding
`flyway_schema_history`) vs. the dev DB, both normalized (comments / blank / psql-meta
lines removed):

- The only textual difference was the rendering of the `CHECK (... = ANY (ARRAY[...]))`
  constraints: the **live dev** dump preserves Hibernate's original
  `ANY((ARRAY[...])::text[])` text, while any constraint that has round-tripped through
  a `CREATE TABLE` is re-serialized by PostgreSQL's expression printer as
  `ANY(ARRAY[(...)::text, ...])`. These are **semantically identical** constraints.

- To eliminate that cosmetic artifact, the dev schema was itself loaded into a clean
  `postgres:16` and re-dumped (same render path as the Flyway-built DB). Diffing that
  against the Flyway/V1-built DB:

  ```
  === DIFF: dev-schema-round-tripped-through-empty-PG  vs  Flyway-V1-built ===
  >>> IDENTICAL — V1 reproduces the dev schema exactly (byte-for-byte, same render path)
  ```

  **Fresh (Flyway V1) == dev, with zero differences.**

---

## Run 2 — EXISTING hand-built DB → Flyway baselines at V1, runs nothing

A `postgres:16` seeded with a copy of the dev schema (all 7 tables present, **no**
`flyway_schema_history`) — i.e. the untracked hand-built DB. A marker row was inserted
first (`inventory.products` id 4242, "DO-NOT-DROP-ME") to prove data survives. Then
`flyway:migrate`:

```
[INFO] Flyway Community Edition 9.22.3 by Redgate
[INFO] Creating Schema History table "public"."flyway_schema_history" with baseline ...
[INFO] Successfully baselined schema with version: 1
[INFO] Schema "public" is up to date. No migration necessary.
```

Schema history after the run:

```
 installed_rank | version |      description      |   type   | success
----------------+---------+-----------------------+----------+---------
              1 | 1       | << Flyway Baseline >> | BASELINE | t
```

- Flyway detected a **non-empty** database, wrote a **BASELINE** row at version 1, and
  reported **"No migration necessary"** — **V1 was never executed**, so no table was
  recreated.
- Data survived intact: `inventory.products` still held the marker row after the run
  (`count = 1`, `name = DO-NOT-DROP-ME`).
- **Idempotent:** re-running `flyway:migrate` a second time produced
  `Schema "public" is up to date. No migration necessary.` — no changes.

---

## Conclusion

| Scenario | What Flyway does | Data | Outcome |
|----------|------------------|------|---------|
| Fresh empty Postgres (e.g. Oracle k3s) | Runs `V1__baseline.sql` | n/a | Schema **identical** to dev |
| Existing hand-built dev DB (or a copy) | Baselines at V1, runs nothing | Preserved | Adopted in place, no drops |

One migration set, one source of truth, both worlds covered. Future changes ship as
new forward migrations (`V2__…`, `V3__…`); `V1__baseline.sql` is never edited again.

> The throwaway containers / network created for these runs
> (`flyway-fresh-pg`, `flyway-existing-pg`, `flyway-verify-net`) were removed after
> verification; the project's own dev database was only ever read from (`pg_dump`),
> never modified.
