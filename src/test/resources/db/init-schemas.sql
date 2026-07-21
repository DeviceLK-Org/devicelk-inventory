-- Mirrors init/01-schemas.sql, which docker-compose runs for the dev database.
-- The schemas must exist before Hibernate's ddl-auto tries to create tables in
-- them, so this runs as the container's init script.
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS cart;
CREATE SCHEMA IF NOT EXISTS orders;
