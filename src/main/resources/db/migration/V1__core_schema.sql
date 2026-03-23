CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE npl_site_boundaries (
  epa_id              varchar(20) PRIMARY KEY,
  site_name           varchar(255) NOT NULL,
  state_code          varchar(2),
  npl_status_code     varchar(10),
  epa_url             text,
  geom                geometry(MultiPolygon, 4326) NOT NULL,
  loaded_at           timestamp NOT NULL DEFAULT now()
);

CREATE INDEX idx_npl_boundaries_geom ON npl_site_boundaries USING GIST(geom);
CREATE INDEX idx_npl_boundaries_state ON npl_site_boundaries(state_code);
CREATE INDEX idx_npl_boundaries_status ON npl_site_boundaries(npl_status_code);

CREATE TABLE npl_site_boundaries_staging (LIKE npl_site_boundaries INCLUDING ALL);

CREATE TABLE npl_human_exposure (
  epa_id                  varchar(20) PRIMARY KEY REFERENCES npl_site_boundaries(epa_id),
  humexposurestscode      varchar(10),
  humanexposurepathdesc   text,
  npl_status              varchar(5),
  site_name               varchar(255),
  loaded_at               timestamp NOT NULL DEFAULT now()
);

CREATE TABLE npl_human_exposure_staging (LIKE npl_human_exposure INCLUDING ALL);

CREATE TABLE site_seo_page_cache (
  epa_id                  varchar(20) PRIMARY KEY REFERENCES npl_site_boundaries(epa_id),
  site_name               varchar(255) NOT NULL,
  state_code              varchar(2),
  exposure_status_code    varchar(10),
  exposure_status_label   varchar(200),
  exposure_pathway_desc   text,
  epa_url                 text,
  computed_at             timestamp NOT NULL DEFAULT now()
);
