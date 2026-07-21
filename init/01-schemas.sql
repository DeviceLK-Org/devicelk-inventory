-- One schema per module of the commerce modular monolith.
-- Runs only on the first startup with an empty data volume
-- (docker-entrypoint-initdb.d); changes require `docker compose down -v`.
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS cart;
CREATE SCHEMA IF NOT EXISTS orders;
