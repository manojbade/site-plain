CREATE TABLE feedback (
  id            bigserial PRIMARY KEY,
  submitted_at  timestamp NOT NULL DEFAULT now(),
  page_type     varchar(20),
  epa_id        varchar(20),
  helpful       boolean,
  comments      text
);
