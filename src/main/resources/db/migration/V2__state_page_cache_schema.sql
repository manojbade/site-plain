CREATE TABLE state_page_cache (
  state_code    varchar(2) PRIMARY KEY,
  state_name    varchar(100) NOT NULL,
  site_count    integer NOT NULL,
  computed_at   timestamp NOT NULL DEFAULT now()
);
