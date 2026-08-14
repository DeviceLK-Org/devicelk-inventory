-- =============================================================================
-- V1__baseline.sql — the DeviceLK inventory schema, one source of truth.
--
-- SCOPE: this service owns the `inventory` schema and nothing else.
--
-- WHY THIS FILE WAS EDITED, WHEN THE RULE SAYS NEVER TO EDIT IT
--   MIGRATION_NOTES.md states that V1 is never edited again and that changes ship
--   as forward migrations. That rule protects databases where V1 has already been
--   applied: editing it there would either be ignored (Flyway checksums the file
--   and would fail validation) or would silently describe a schema that no live
--   database actually has.
--
--   Neither applied here. This baseline had only ever run on one developer laptop
--   and had never been pushed, so there was no deployed database whose history it
--   was the record of. The alternative — keeping the original V1 and shipping a V2
--   that DROPs the cart and orders schemas — would mean every fresh inventory
--   database creates cart and order tables and then destroys them, and would leave
--   the baseline describing two modules this service no longer contains. A
--   baseline that lies about its own service is worse than a rule bent once, on
--   purpose, and written down.
--
--   From here the rule resumes: this file is frozen, and changes ship as V2, V3, …
--
-- WHAT WAS REMOVED FROM THE PREVIOUS VERSION
--   The `cart` and `orders` schemas and their tables, plus `public.event_publication`,
--   all of which moved to DeviceLK-Commerce's own V1 in its own database. There were
--   no cross-schema foreign keys to untangle — modules referenced each other by bare
--   id, never by FK, which is exactly what made the extraction a change of transport
--   rather than a data migration.
--
--   `public.event_publication` went with them because it is Spring Modulith's
--   event-publication registry, and this service publishes no application events.
--   The events it once carried — OrderPlacedEvent — are the commerce service's.
--
-- HOW THIS FILE WAS ORIGINALLY GENERATED
--   Dumped from the real hand-built dev database, never hand-typed from memory:
--       pg_dump --schema-only --no-owner --no-privileges devicelk_commerce
--   then stripped of owner/privilege/tablespace noise and of pg_dump's psql-only
--   \restrict / \unrestrict / SET meta-lines (Flyway runs SQL over JDBC and does
--   not understand backslash commands). Type and nullability are reproduced
--   verbatim — including the money-as-integer-cents columns (*_cents bigint).
--
--   No IF NOT EXISTS: on a fresh empty database this runs top to bottom; if it ever
--   collided with pre-existing objects we WANT the loud failure, because that would
--   mean the schema had drifted from this baseline.
--
-- ARCHITECTURE-NEUTRAL: no extensions, no tablespaces, no x86-only features — this
-- runs unchanged on the ARM PostgreSQL target (Oracle Cloud k3s).
-- =============================================================================

CREATE SCHEMA inventory;


-- --- products --------------------------------------------------------------------
CREATE TABLE inventory.products (
    id bigint NOT NULL,
    brand character varying(255) NOT NULL,
    category character varying(30) NOT NULL,
    description character varying(1000),
    name character varying(255) NOT NULL,
    price_cents bigint NOT NULL,
    currency character(3) DEFAULT 'LKR'::bpchar NOT NULL,
    CONSTRAINT products_category_check CHECK (((category)::text = ANY ((ARRAY['LAPTOP'::character varying, 'SMARTPHONE'::character varying, 'TABLET'::character varying, 'ACCESSORIES'::character varying, 'AUDIO_DEVICE'::character varying])::text[])))
);

CREATE SEQUENCE inventory.products_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE inventory.products_id_seq OWNED BY inventory.products.id;

ALTER TABLE ONLY inventory.products ALTER COLUMN id SET DEFAULT nextval('inventory.products_id_seq'::regclass);


-- --- stock -----------------------------------------------------------------------
-- Separate from products because the access profile is entirely different:
-- catalogue rows are read constantly and written rarely by an administrator, while
-- these quantities are contended on every checkout. The `version` column is the
-- optimistic lock that settles two checkouts racing for the last units.
CREATE TABLE inventory.stock (
    product_id bigint NOT NULL,
    available_qty integer NOT NULL,
    reserved_qty integer DEFAULT 0 NOT NULL,
    min_stock_threshold integer NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT stock_available_qty_check CHECK ((available_qty >= 0)),
    CONSTRAINT stock_min_threshold_check CHECK ((min_stock_threshold >= 0)),
    CONSTRAINT stock_reserved_qty_check CHECK ((reserved_qty >= 0))
);

ALTER TABLE ONLY inventory.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);

ALTER TABLE ONLY inventory.products
    ADD CONSTRAINT uc_product_name_brand UNIQUE (name, brand);

ALTER TABLE ONLY inventory.stock
    ADD CONSTRAINT stock_pkey PRIMARY KEY (product_id);

ALTER TABLE ONLY inventory.stock
    ADD CONSTRAINT fk_stock_product FOREIGN KEY (product_id) REFERENCES inventory.products(id) ON DELETE CASCADE;
