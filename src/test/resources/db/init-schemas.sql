-- Mirrors init/01-schemas.sql and init/02-event-publication.sql, which
-- docker-compose runs for the dev database. Testcontainers takes a single init
-- script, so both are combined here — keep them in step.
--
-- The schemas must exist before Hibernate's ddl-auto tries to create tables in
-- them, so this runs as the container's init script.
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS cart;
CREATE SCHEMA IF NOT EXISTS orders;

-- Spring Modulith's event publication registry. Created here rather than left to
-- ddl-auto because Modulith maps `serializedEvent` as an unqualified String,
-- which Hibernate renders as VARCHAR(255) — too small for the JSON of any event
-- carrying a collection. The failing INSERT happens inside the publishing
-- transaction, so it rolls back the business operation too: a checkout with two
-- lines fails outright while a one-line checkout succeeds. See
-- init/02-event-publication.sql for the full explanation.
--
-- ddl-auto: update adds missing tables and columns but never alters an existing
-- column's type, so this definition is the one that survives.
CREATE TABLE IF NOT EXISTS event_publication (
    id               UUID                     NOT NULL,
    listener_id      TEXT                     NOT NULL,
    event_type       TEXT                     NOT NULL,
    serialized_event TEXT                     NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS ix_event_publication_completion_date
    ON event_publication (completion_date);
