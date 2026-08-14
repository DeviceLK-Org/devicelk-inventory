# DeviceLK — Inventory / Commerce service

Spring Boot **Modulith** (Java 21, Maven) backing the DeviceLK commerce domain.
It is one deployable that hosts several modules — `inventory`, `cart`, `orders` —
each owning its own PostgreSQL schema inside a single `devicelk_commerce` database.
Cross-module communication goes through the Spring Modulith event-publication
registry (the `public.event_publication` table).

Local infrastructure (PostgreSQL 16) is defined in [`docker-compose.yml`](docker-compose.yml);
copy `.env.example` to `.env` first.

## Database & migrations

The schema is managed by **Flyway** (`flyway-core`, pinned to **9.22.3** — the version
Spring Boot 3.2.4 manages, the first Flyway line that recognises PostgreSQL 16, and
pure-Java so it runs unchanged on the ARM Postgres target). Flyway is **the single
source of truth** for the schema and runs automatically before Hibernate on startup.

### How it works

- Migrations live in [`src/main/resources/db/migration`](src/main/resources/db/migration)
  and follow Flyway's naming convention: `V<version>__<description>.sql`
  (double underscore), applied in ascending version order.
- [`V1__baseline.sql`](src/main/resources/db/migration/V1__baseline.sql) is the full
  current schema — every table, sequence, constraint, check ("enum") and index across
  the `inventory`, `cart`, `orders` and `public` schemas, including the money columns
  stored as integer cents (`*_cents bigint`) and the TEXT-widened `event_publication`
  table. It was generated from the real database with
  `pg_dump --schema-only --no-owner --no-privileges`, not written from memory.
- Flyway records applied migrations in a `flyway_schema_history` table.

### Bringing up a fresh database

A brand-new empty Postgres needs nothing special — start the app and Flyway runs
`V1__baseline.sql` from scratch, producing the exact current schema:

```bash
cp .env.example .env          # set POSTGRES_USER / POSTGRES_PASSWORD
docker compose up -d          # PostgreSQL 16 on localhost:5433
mvn spring-boot:run           # Flyway migrates on startup, then the app boots
```

> The `./init` scripts only run on a first-boot **empty** docker volume and just
> pre-seed the schemas; on such a volume the app then sees a non-empty DB and
> **baselines** it (see below). Against a truly empty Postgres with no init scripts —
> the Oracle Cloud k3s target — Flyway builds everything from `V1`.

### The baseline decision (existing hand-built DB vs. fresh DB)

The current dev database was built by hand (Hibernate `ddl-auto` plus a few manual
`ALTER`s that were never tracked) and already contains the schema and live data, but
has no `flyway_schema_history`. To adopt it without re-running anything or losing
data, Flyway is configured in [`application.yml`](src/main/resources/application.yml):

```yaml
spring:
  flyway:
    baseline-on-migrate: true   # adopt a non-empty DB by writing a baseline row...
    baseline-version: 1         # ...at version 1, instead of running V1 against it
    locations: classpath:db/migration
```

This gives one migration set that serves both worlds:

- **Existing DB** (schema already present, no history): Flyway writes a baseline row
  at V1 and treats V1 as already applied — **V1 never runs, so nothing is recreated
  and no data is dropped.**
- **Fresh empty DB**: Flyway has nothing to baseline, so it runs V1 from scratch.

Ownership of the Modulith registry table is settled the same way: **Flyway owns
`public.event_publication`** (it is created in `V1`), and Modulith's own JDBC schema
initialisation is left disabled — a single source of truth, no duplicate DDL.

### Adding future migrations

Never edit `V1__baseline.sql` after it has been applied anywhere — Flyway validates
checksums and a change would fail startup. Instead add a new forward migration:

```
src/main/resources/db/migration/V2__add_orders_paid_at.sql
src/main/resources/db/migration/V3__....sql
```

Keep migrations schema-qualified (`inventory.`, `cart.`, `orders.`, `public.`) and
architecture-neutral (no extensions or x86-only features) so they run on the ARM
Postgres target. Each `V<n>` runs once, in order, on every database — existing and
fresh alike.
