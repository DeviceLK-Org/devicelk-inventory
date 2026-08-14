-- =============================================================================
-- V1__baseline.sql — the complete DeviceLK commerce schema, one source of truth.
--
-- HOW THIS FILE WAS GENERATED
--   Dumped from the real hand-built dev database, never hand-typed from memory:
--       pg_dump --schema-only --no-owner --no-privileges devicelk_commerce
--   then stripped of owner/privilege/tablespace noise and of pg_dump's psql-only
--   \restrict / \unrestrict / SET meta-lines (Flyway runs SQL over JDBC and does
--   not understand backslash commands). Every table, sequence, constraint, check
--   ("enum") and index the dev DB has is reproduced below, verbatim in type and
--   nullability — including the money-as-integer-cents columns (*_cents bigint)
--   and the TEXT-widened event_publication table.
--
--   No IF NOT EXISTS: on a fresh empty database this runs top to bottom; if it
--   ever collided with pre-existing objects we WANT the loud failure, because that
--   would mean the schema had drifted from this baseline.
--
-- EVENT PUBLICATION TABLE — OWNERSHIP DECISION
--   Spring Modulith's JPA event-publication registry needs a public.event_publication
--   table. FLYWAY OWNS IT (it is created here in V1), so there is a single source of
--   truth for the schema. Modulith's own JDBC schema-initialisation is left disabled
--   (spring.modulith.events.jdbc.schema-initialization.enabled defaults to false and
--   we do not turn it on), so Modulith never tries to create or alter this table.
--
--   The column types below are the REAL current dev types: all columns TEXT/nullable
--   except the id primary key. This is deliberately the hand-widened shape — Modulith
--   maps serialized_event as a plain String, which Hibernate would otherwise create as
--   VARCHAR(255); a multi-line OrderPlacedEvent overflows 255 chars and rolls back the
--   whole checkout. Baking TEXT into V1 makes that impossible on any fresh database.
--
-- ARCHITECTURE-NEUTRAL: no extensions, no tablespaces, no x86-only features — this
-- runs unchanged on the ARM PostgreSQL target (Oracle Cloud k3s).
-- =============================================================================

-- --- Schemas: one per module of the commerce modular monolith --------------------
CREATE SCHEMA cart;
CREATE SCHEMA inventory;
CREATE SCHEMA orders;


-- --- inventory module ------------------------------------------------------------
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


-- --- cart module -----------------------------------------------------------------
CREATE TABLE cart.carts (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    status character varying(20) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    user_id character varying(255) NOT NULL,
    CONSTRAINT carts_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'CHECKED_OUT'::character varying, 'ABANDONED'::character varying])::text[])))
);

CREATE TABLE cart.cart_items (
    id uuid NOT NULL,
    currency character(3) NOT NULL,
    product_id bigint NOT NULL,
    quantity integer NOT NULL,
    unit_price_cents bigint NOT NULL,
    cart_id uuid NOT NULL,
    CONSTRAINT ck_cart_item_quantity_positive CHECK ((quantity > 0))
);

ALTER TABLE ONLY cart.carts
    ADD CONSTRAINT carts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY cart.cart_items
    ADD CONSTRAINT cart_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY cart.cart_items
    ADD CONSTRAINT uc_cart_item_cart_product UNIQUE (cart_id, product_id);

ALTER TABLE ONLY cart.cart_items
    ADD CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES cart.carts(id);

CREATE INDEX ix_carts_user_id_status ON cart.carts USING btree (user_id, status);

-- Enforces "one ACTIVE cart per user" — a partial unique index an in-memory DB
-- could not model, which is why the cart tests run on real PostgreSQL.
CREATE UNIQUE INDEX ux_carts_one_active_per_user ON cart.carts USING btree (user_id) WHERE ((status)::text = 'ACTIVE'::text);


-- --- orders module ---------------------------------------------------------------
CREATE TABLE orders.orders (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    currency character(3) NOT NULL,
    status character varying(20) NOT NULL,
    total_cents bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    user_id character varying(255) NOT NULL,
    CONSTRAINT orders_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PAID'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])))
);

CREATE TABLE orders.order_items (
    id uuid NOT NULL,
    currency character(3) NOT NULL,
    product_id bigint NOT NULL,
    product_name character varying(255) NOT NULL,
    quantity integer NOT NULL,
    unit_price_cents bigint NOT NULL,
    order_id uuid NOT NULL,
    CONSTRAINT ck_order_item_quantity_positive CHECK ((quantity > 0))
);

ALTER TABLE ONLY orders.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);

ALTER TABLE ONLY orders.order_items
    ADD CONSTRAINT order_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY orders.order_items
    ADD CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders.orders(id);

CREATE INDEX ix_orders_user_id_created_at ON orders.orders USING btree (user_id, created_at DESC);


-- --- Spring Modulith event publication registry (public schema) ------------------
-- See "EVENT PUBLICATION TABLE — OWNERSHIP DECISION" in the header. TEXT columns are
-- intentional; only id is NOT NULL, matching the real registry table in the dev DB.
CREATE TABLE public.event_publication (
    id uuid NOT NULL,
    completion_date timestamp(6) with time zone,
    event_type text,
    listener_id text,
    publication_date timestamp(6) with time zone,
    serialized_event text
);

ALTER TABLE ONLY public.event_publication
    ADD CONSTRAINT event_publication_pkey PRIMARY KEY (id);
