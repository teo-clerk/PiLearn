-- ────────────────────────────────────────────────────────────────────────────
-- PiLearn — local database bootstrap
--
-- Runs once, on first `docker compose up`, against an empty data volume.
-- The POSTGRES_DB / POSTGRES_USER supplied to the image already exist by the
-- time this executes, so this file only has to create the application schema
-- and hand it to the app user. Liquibase creates the tables at boot.
--
-- Replaces backend/src/main/resources/db/initdb.sql, which was not valid
-- PostgreSQL: it wrote `create user web with password "web"`, and double quotes
-- denote an identifier rather than a string literal, so the statement errored.
-- ────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS pianoml;

DO $$
BEGIN
  EXECUTE format('ALTER SCHEMA pianoml OWNER TO %I', current_user);
  EXECUTE format('GRANT ALL ON SCHEMA pianoml TO %I', current_user);
END
$$;

-- Liquibase writes its bookkeeping tables into the default schema.
ALTER DATABASE pianoml SET search_path TO pianoml, public;
