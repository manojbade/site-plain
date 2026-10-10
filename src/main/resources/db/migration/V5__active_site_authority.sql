-- EPA's human exposure feed (npl_human_exposure.npl_status, from the feed's nplstatus) decides
-- whether a site is active (Final). The boundary file's npl_status_code is used only when a site
-- has no exposure row. Every feed row is now stored, including Final sites EPA has not published
-- a boundary for, so the foreign keys to npl_site_boundaries are dropped. The loaders and the
-- active_npl_sites view keep the tables consistent.

ALTER TABLE npl_human_exposure DROP CONSTRAINT IF EXISTS npl_human_exposure_epa_id_fkey;
ALTER TABLE site_seo_page_cache DROP CONSTRAINT IF EXISTS site_seo_page_cache_epa_id_fkey;

ALTER TABLE npl_human_exposure
  ADD COLUMN state_code varchar(2),
  ADD COLUMN epa_url text;
ALTER TABLE npl_human_exposure_staging
  ADD COLUMN state_code varchar(2),
  ADD COLUMN epa_url text;

ALTER TABLE site_seo_page_cache
  ADD COLUMN has_boundary boolean NOT NULL DEFAULT true;

-- The one definition of an active site. Every page, count and lookup reads from this view.
-- geom is null (and has_boundary false) for active sites with no published boundary.
CREATE VIEW active_npl_sites AS
SELECT
  COALESCE(s.epa_id, h.epa_id)         AS epa_id,
  COALESCE(s.site_name, h.site_name, s.epa_id, h.epa_id) AS site_name,
  COALESCE(s.state_code, h.state_code) AS state_code,
  COALESCE(s.epa_url, h.epa_url)       AS epa_url,
  COALESCE(h.npl_status, s.npl_status_code) AS npl_status,
  h.humexposurestscode,
  h.humanexposurepathdesc,
  s.geom,
  (s.epa_id IS NOT NULL)               AS has_boundary
FROM npl_site_boundaries s
FULL OUTER JOIN npl_human_exposure h ON h.epa_id = s.epa_id
WHERE COALESCE(h.npl_status, s.npl_status_code) = 'F';
