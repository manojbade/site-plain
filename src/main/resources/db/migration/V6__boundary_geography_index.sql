-- The lookup measures distance on geom::geography. The plain geometry index cannot serve that
-- expression, and since V5 the status filter sits above a FULL JOIN, so without this index every
-- lookup ran ST_DWithin on every boundary row (about 0.8 s on prod data). A plain CREATE INDEX
-- takes a SHARE lock, which blocks only writes; the running app only reads this table.
CREATE INDEX idx_npl_boundaries_geog ON npl_site_boundaries USING GIST ((geom::geography));
ANALYZE npl_site_boundaries;
