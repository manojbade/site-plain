# 01: EPA NPL Superfund Site Boundaries

Polygon boundaries for every EPA National Priorities List (Superfund) site. This is the spatial backbone of Site Plain: it answers "which Superfund sites are near this address" and drives every `/site/{epa_id}` and `/state/{code}` page.

## Upstream

- Dataset (data.gov): https://catalog.data.gov/dataset/npl-superfund-site-boundaries-epa (previously `...-epa10`, which now 404s)
- Direct GDB download: `https://edg.epa.gov/data/PUBLIC/OLEM/OLEM-OSRTI/NPL_Boundaries.zip` (~21 MB zipped)
- Hosted feature service (not used by the loader): `https://services.arcgis.com/cJ9YHowT8TU7DUyn/arcgis/rest/services/FAC_Superfund_Site_Boundaries_EPA_Public/FeatureServer`
- Cadence: EPA's SEGS harvests regional data weekly, but the set of sites and their boundaries changes rarely. Refresh roughly annually, or when data.gov `metadata_modified` shows a meaningful change.
- Provenance: the zip is unversioned at that URL. Note the EDG `Last-Modified` header when you download (e.g. 2026-07-11) and put it in the GitHub release notes.

## Why a Python pre-conversion step

The raw file is an ESRI File Geodatabase (`.gdb`). The Java stack has no GeoTools, so it cannot read GDB. `scripts/convert_npl_to_geojson.py` uses `geopandas`/`pyogrio` to read the `SITE_BOUNDARIES_SF` layer, deduplicate, and write GeoJSON (EPSG:4326). The GeoJSON is uploaded to a GitHub release; the app loads that.

The GDB has six layers. Only `SITE_BOUNDARIES_SF` (MultiPolygon, one whole-site footprint) is used. The others (`OU_BOUNDARIES_SF`, `IC_BOUNDARIES_SF`, `SITE_FEATURE_*`) are ignored.

## Deduplication

A single `EPA_ID` can have multiple boundary polygons of different `SITE_FEATURE_TYPE`. The script keeps only the rows at the highest priority present for each site (highest first): Comprehensive Site Area, Total Site Polygon/OU Aggregation, Site Boundary, Current Ground Boundary, OU Boundary Aggregation, Extent of Contamination, Contamination Boundary (Groundwater), Contamination Boundary, Other. Null or unmapped types (`<Null>`, `Waste in Place`, `OU Boundary`, and similar variants) rank last.

Before ranking, rows whose `SITE_FEATURE_NAME` contains "Source Control" are dropped. These are drainage basins mapped for upland source control, filed under the site's top feature type but not its footprint: WA0002329803 "LDW Uplands Source Control Area" (WA Department of Ecology, 80.6 km2 against the 1.8 km2 waterway), ORSFN1002155 "Portland Harbor Uplands ODEQ Source Control" (Oregon DEQ, 32.6 km2), and WAD980726368 "CBNSTF OU 5 Source Control Area" (never the winning type, so no change). Unioning the first two put homes miles from the Lower Duwamish "inside" the site.

When several rows tie at that priority, their geometries are **unioned** into one MultiPolygon. Example: ORSFN1002155 Portland Harbor has 99 Comprehensive Site Area polygons after the drop; the union is about 18.2 km2 (EPA's 8.5 km2 in-water area plus the upland cleanup properties filed with it), where the old one-row pick kept a 0.013 km2 fragment. Lower Duwamish is about 5.2 km2.

Union review gate: a tied union is flagged when one piece adds more than 2 km2 (`--union-review-km2`) and grows the union of the other pieces by more than 1.5x. Every flagged site must be in the script's `REVIEWED_UNIONS` with the largest area accepted after checking EPA's site description, or the script fails and writes nothing; a reviewed site whose union grows past that area fails too. In July six sites are flagged and reviewed: Eielson AFB, Eastern Michaud Flats, Black Butte Mine, Portland Harbor, Midnite Mine and Moses Lake Wellfield. The July 2026 GDB has 19 tied sites (18 Final). Attributes come from one row of the winning group, chosen deterministically: non-null `NPL_STATUS_CODE`, then non-null `STATE_CODE`, then latest `LAST_CHANGE_DATE`, then lowest GDB feature id. Invalid geometry is repaired with `make_valid` (24 sites in July, no area change) and every output geometry is a MultiPolygon.

Why: a consumer asking "how close am I?" should get the full designated site area, not a sub-boundary that would make the site look farther away.

Reference at 2026-07-11: 2,114 raw features, 1,908 unique sites after dedup, 1,328 with `NPL_STATUS_CODE = 'F'`.

## Input checks

The converter refuses to write output (exit code 2) when the raw layer has more than 0.5% of rows with a null or blank `EPA_ID`, `NPL_STATUS_CODE` or `STATE_CODE`, when the Final count is outside 1,200 to 1,450, or when a Final site has no state. The thresholds are flags (`--max-null-rate`, `--min-final`, `--max-final`). The 2026-10-10 EDG release fails these checks (5.35% null status, 11.35% null state) and must not be published.

Self-check without a GDB: `python3 scripts/convert_npl_to_geojson.py --self-test`.

## Status filtering happens at query time, not conversion

The conversion script and the loader keep **all** statuses (~1,908 rows: F, D, N, P, R, A, S). Whether a site is active is decided at read time by the `active_npl_sites` view (migration V5): EPA's human exposure feed `nplstatus` wins, and this file's `NPL_STATUS_CODE` is used only for sites with no exposure row. See [source 02](02-epa-human-exposure.md#active-sites). So `SELECT count(*) FROM npl_site_boundaries` returns ~1,908, while the user-visible count is `SELECT count(*) FROM active_npl_sites` (1,337 with the 2026-10-10 feed: 1,322 with a boundary plus 15 without).

## Fields used

`EPA_ID`, `SITE_NAME`, `STATE_CODE`, `NPL_STATUS_CODE`, `URL_ALIAS_TXT`, and `geometry`. `EPA_ID` and `STATE_CODE` are uppercased on load.

## Load mechanics

`SourceDownloader` fetches `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` to a temp file (redirects followed; 300 s whole-download timeout and 240 MB cap by default; non-2xx, `text/html`, empty or non-JSON bodies rejected) and computes its SHA-256.

`NplBoundaryLoader.loadIntoStaging(file)`:
1. Truncates `npl_site_boundaries_staging`.
2. Streams the GeoJSON with a `JsonParser`, one feature at a time; each geometry is copied from the parser as text, never built as a tree (a full reload fits in a 128 MB heap). Each feature is inserted into staging with a plain `INSERT` as `ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(...), 4326))`, so a Polygon is stored as a MultiPolygon.
3. Records what it saw. `DataBootstrapService` rejects the file, and keeps the live boundaries, when: the top-level type is not `FeatureCollection`; `crs` is not CRS84 or EPSG:4326; `EPA_ID`, `SITE_NAME`, `STATE_CODE` or `NPL_STATUS_CODE` is missing on more than 0.5% of features; an `EPA_ID` repeats; a geometry is not a Polygon or MultiPolygon with numeric positions nested correctly; a coordinate is outside longitude -180..180 or latitude -90..90; the Final count is outside 1,200..1,450 or a Final feature has no state (the converter's gates); fewer than 90% of the live rows were staged; fewer than 90% of the projected active sites would have both a boundary and a feed row (the file's EPA_IDs do not match the live feed; 1,322 of 1,337 on real data); or the active site count would move more than 5% either way (see `SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT` in the [README](../README.md)). The rejection message lists every failed check with counts and up to five example IDs.

The swap (`NplBoundaryRepository.replaceLiveFromStaging`) runs inside the single refresh transaction together with the exposure swap and both page cache rebuilds: `DELETE FROM npl_site_boundaries` then `INSERT ... SELECT` from staging. Readers keep the old rows until commit. After commit the table is analyzed and staging is emptied. The source URL, SHA-256, size and Last-Modified are recorded in `data_source_state` (V7).

The GIST indexes (`idx_npl_boundaries_geom` from V1, `idx_npl_boundaries_geog` from V6) persist across the swap.

A valid Final-site spot-check ID is `GAD990855074` (Firestone Tire & Rubber Co., Albany Plant, GA). Do not reuse older notes' `GA8213820731`; that ID is not present in the EPA data.

## Refresh

Follow the standard procedure in the [README](../README.md). Per-source specifics: place the extracted `.gdb` at `/Users/manojbade/workspace/Info/superfund/superfund_audit_data/NPL_Boundaries/NPL_Boundaries.gdb`, or pass `--gdb PATH`, then run the converter. `--output PATH` writes somewhere other than `scripts/npl_site_boundaries.geojson`.

## Quirks

- `pyogrio` prints an `organizePolygons() received a polygon with more than 100 parts` warning for a few complex sites. Harmless.
- The raw GDB is not committed and not on prod. Prod only ever loads the GitHub release GeoJSON.
- The converted GeoJSON (`scripts/npl_site_boundaries.geojson`) is gitignored.
