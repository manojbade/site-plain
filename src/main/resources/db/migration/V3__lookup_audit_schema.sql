CREATE TABLE lookup_audit (
  id              bigserial PRIMARY KEY,
  lookup_at       timestamp NOT NULL DEFAULT now(),
  state_code      varchar(2),
  result_count    integer,
  nearest_miles   numeric(8,3),
  geocoder_used   varchar(20),
  resolved        boolean NOT NULL
);

CREATE INDEX idx_lookup_audit_at ON lookup_audit(lookup_at);
CREATE INDEX idx_lookup_audit_state ON lookup_audit(state_code);
