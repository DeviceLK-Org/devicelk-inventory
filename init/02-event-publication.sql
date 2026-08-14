-- Spring Modulith's event publication registry, created explicitly.
--
-- WHY THIS FILE EXISTS
-- Modulith's JPA registry maps `serializedEvent` as a plain String with no
-- length and no @Lob, so Hibernate's ddl-auto creates it as VARCHAR(255). The
-- column holds the JSON of every published event, and an OrderPlacedEvent for an
-- order with more than one line exceeds 255 characters immediately — so the
-- INSERT fails, and because that insert happens inside the checkout transaction,
-- *the entire checkout is rolled back*. The customer gets a 500 and no order.
-- A single-line order fits, which is what makes this so easy to miss: the happy
-- path works and the bug appears only once a basket has two things in it.
--
-- Creating the table here, before Hibernate looks at it, avoids the problem
-- entirely: `ddl-auto: update` adds missing tables and columns but never alters
-- the type of a column that already exists, so this definition wins. The column
-- types below match the schema Spring Modulith ships for its JDBC registry,
-- which is the same registry with the same contents.
--
-- Runs only on the first startup with an empty data volume
-- (docker-entrypoint-initdb.d); changes require `docker compose down -v`.
--
-- FOR AN EXISTING DATABASE this file will not run. Fix it in place with:
--   ALTER TABLE event_publication ALTER COLUMN serialized_event TYPE TEXT;
--   ALTER TABLE event_publication ALTER COLUMN event_type       TYPE TEXT;
--   ALTER TABLE event_publication ALTER COLUMN listener_id      TYPE TEXT;

CREATE TABLE IF NOT EXISTS event_publication (
    id               UUID                     NOT NULL,
    listener_id      TEXT                     NOT NULL,
    event_type       TEXT                     NOT NULL,
    serialized_event TEXT                     NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

-- Republishing outstanding events on restart scans for rows with no completion
-- date; without this that scan is a full table scan over every event ever
-- published, and the table only grows.
CREATE INDEX IF NOT EXISTS ix_event_publication_completion_date
    ON event_publication (completion_date);
