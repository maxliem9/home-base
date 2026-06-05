-- Align todos.status / todos.priority with the application model.
--
-- Exposed (Tables.kt) maps both columns as varchar and validates the allowed values in
-- application code, but V2 created them as PostgreSQL ENUM types (todo_status /
-- todo_priority). PostgreSQL implicitly casts enum -> text on read, so SELECTs worked,
-- but it rejects the plain-text bindings Exposed sends on INSERT/UPDATE:
--   ERROR: column "status" is of type todo_status but expression is of type character varying
-- That made every "create todo" request fail with a 500. Tests never caught it because
-- they build the schema from Exposed via SchemaUtils on H2 (varchar), bypassing this enum.
--
-- Convert the columns to varchar to match the code, then drop the now-unused enum types.

ALTER TABLE todos ALTER COLUMN status DROP DEFAULT;
ALTER TABLE todos ALTER COLUMN status TYPE VARCHAR(20) USING status::text;
ALTER TABLE todos ALTER COLUMN status SET DEFAULT 'INBOX';

ALTER TABLE todos ALTER COLUMN priority TYPE VARCHAR(10) USING priority::text;

DROP TYPE IF EXISTS todo_status;
DROP TYPE IF EXISTS todo_priority;
