-- One schema per service (own-your-data); services run Flyway/JPA migrations inside their schema.
CREATE SCHEMA IF NOT EXISTS orders;
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS operations;
