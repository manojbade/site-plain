-- One row per dataset: the identity of the data now live (source_url, sha256, bytes, the HTTP
-- Last-Modified, row counts, loaded_at) plus the last refresh attempt (last_checked_at, last_status,
-- last_error). A failed attempt changes only the last_* columns, so the identity always describes
-- what is live. Boundaries reload when the configured URL differs from source_url (or on first boot,
-- or with SITEPLAIN_DATA_REFRESH_ON_STARTUP=true), replacing the old 90-day age trigger, and an
-- exposure feed whose sha256 matches is not swapped in again.
--
-- New, empty table: nothing in the running app reads it, so this is safe while the old deploy serves.
-- On the first boot with existing boundaries and no row here, the app adopts the configured URL as
-- the live source (last_status ADOPTED, sha256 null) instead of reloading.
CREATE TABLE data_source_state (
  dataset          varchar(32) PRIMARY KEY,
  source_url       text,
  sha256           char(64),
  bytes            bigint,
  last_modified    timestamptz,
  row_count        integer,
  final_count      integer,
  loaded_at        timestamptz,
  last_checked_at  timestamptz NOT NULL DEFAULT now(),
  last_status      varchar(16) NOT NULL,
  last_error       text,
  CONSTRAINT data_source_state_dataset_check CHECK (dataset IN ('npl_boundaries', 'human_exposure')),
  CONSTRAINT data_source_state_status_check CHECK (last_status IN ('LOADED', 'UNCHANGED', 'ADOPTED', 'FAILED'))
);
