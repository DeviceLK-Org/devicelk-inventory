# DeviceLK — Inventory service

Spring Boot (Java 21, Maven) owning the DeviceLK **product catalogue and stock**,
and nothing else. It exposes two APIs:

- **REST on :8081** (`/inventory/**`) — catalogue CRUD, used by the admin portal
  through the API gateway.
- **gRPC on :9090** (`ProductGrpcService`) — the interface other services use.
  DeviceLK-AIRetrieval reads the catalogue through it; DeviceLK-Commerce reads it
  *and* drives the stock reservation lifecycle (`ReserveStock`, `ReleaseStock`,
  `ConfirmReservation`).

Its database is `devicelk_inventory`, containing the `inventory` schema alone.

> **History.** This service was briefly a Spring Modulith monolith hosting
> `inventory`, `cart` and `orders` modules in one `devicelk_commerce` database.
> Cart and order were extracted into **DeviceLK-Commerce** and now live in their own
> service and their own database. The extraction was cheap because the modules had
> always referenced each other through published facades and by bare id rather than
> by foreign key — so it changed the transport, not the model. What it could not
> preserve is checkout's single transaction across all three; see DeviceLK-Commerce
> for the compensating saga that replaced it.
>
> Spring Modulith is still on the classpath, for boundary enforcement only
> (`ModularityTests`). The event-publication registry went to DeviceLK-Commerce with
> the order module, since this service publishes no application events.

## Local development

The shared dev PostgreSQL lives here and serves **both** services — one container,
two databases (`devicelk_inventory` and `devicelk_commerce`). DeviceLK-Commerce
ships no compose file of its own and depends on this one.

```bash
cp .env.example .env          # set POSTGRES_USER / POSTGRES_PASSWORD
docker compose up -d          # PostgreSQL 16 on localhost:5433, both databases
mvn spring-boot:run           # Flyway migrates on startup, then the app boots
```

`./init/00-create-commerce-database.sql` creates the second database on first boot
of an empty volume. It creates **no schemas** — each service's Flyway baseline does
that for its own database. That is deliberate: pre-creating schemas makes the
database non-empty, which flips `baseline-on-migrate` into *adopting* it and
skipping `V1` entirely, leaving the tables to be built by Hibernate and the
migration untested.

## Database & migrations

The schema is managed by **Flyway** (`flyway-core`, pinned to **9.22.3** — the
version Spring Boot 3.2.4 manages, the first Flyway line that recognises
PostgreSQL 16, and pure-Java so it runs unchanged on the ARM Postgres target).
Flyway is **the single source of truth** and runs before Hibernate on startup.

`spring.jpa.hibernate.ddl-auto` is **`validate`**. Hibernate checks the schema and
never changes it; a mapping that has drifted from the migrations is a startup
failure here rather than a surprise on the first database where `ddl-auto` was
never allowed to run.

### How it works

- Migrations live in [`src/main/resources/db/migration`](src/main/resources/db/migration)
  and follow Flyway's naming convention: `V<version>__<description>.sql`
  (double underscore), applied in ascending version order.
- [`V1__baseline.sql`](src/main/resources/db/migration/V1__baseline.sql) is the full
  current schema — `inventory.products`, `inventory.stock` and their sequences,
  constraints, checks and indexes, including the money columns stored as integer
  cents (`*_cents bigint`). It was generated from the real database with
  `pg_dump --schema-only --no-owner --no-privileges`, not written from memory.
- Flyway records applied migrations in a `flyway_schema_history` table.

### The baseline decision (existing hand-built DB vs. fresh DB)

The original dev database was built by hand (Hibernate `ddl-auto` plus a few manual
`ALTER`s that were never tracked) and already contained the schema and live data,
but had no `flyway_schema_history`. To adopt such a database without re-running
anything or losing data, Flyway is configured in
[`application.yml`](src/main/resources/application.yml):

```yaml
spring:
  flyway:
    baseline-on-migrate: true   # adopt a non-empty DB by writing a baseline row...
    baseline-version: 1         # ...at version 1, instead of running V1 against it
    locations: classpath:db/migration
```

One migration set therefore serves both worlds:

- **Existing DB** (schema already present, no history): Flyway writes a baseline row
  at V1 and treats V1 as already applied — **V1 never runs, so nothing is recreated
  and no data is dropped.**
- **Fresh empty DB**: Flyway has nothing to baseline, so it runs V1 from scratch.

### Adding future migrations

`V1__baseline.sql` was edited exactly once, during the commerce extraction, to
remove the `cart` and `orders` schemas and `public.event_publication`. That was
possible only because it had never been applied outside one developer laptop and
had never been pushed — see [`MIGRATION_NOTES.md`](MIGRATION_NOTES.md). **The rule
has resumed: never edit it again.** Flyway validates checksums, and a change to an
applied migration fails startup.

Add a forward migration instead:

```
src/main/resources/db/migration/V2__add_product_barcode.sql
src/main/resources/db/migration/V3__....sql
```

Keep migrations schema-qualified (`inventory.`) and architecture-neutral (no
extensions or x86-only features) so they run on the ARM Postgres target. Each
`V<n>` runs once, in order, on every database — existing and fresh alike.
