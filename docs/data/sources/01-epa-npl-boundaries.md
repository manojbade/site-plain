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

A single `EPA_ID` can have multiple boundary polygons of different `SITE_FEATURE_TYPE`. The script keeps one per site by this priority (highest first): Comprehensive Site Area, Total Site Polygon/OU Aggregation, Site Boundary, Current Ground Boundary, OU Boundary Aggregation, Extent of Contamination, Contamination Boundary (Groundwater), Contamination Boundary, Other.

Why: a consumer asking "how close am I?" should get the full designated site area, not a sub-boundary that would make the site look farther away.

Reference at 2026-07-11: 2,114 raw features, 1,908 unique sites after dedup, 1,328 with `NPL_STATUS_CODE = 'F'`.

## Status filtering happens at query time, not conversion

The conversion script and the loader keep **all** statuses (~1,908 rows: F, D, N, P, R, A, S). Whether a site is active is decided at read time by the `active_npl_sites` view (migration V5): EPA's human exposure feed `nplstatus` wins, and this file's `NPL_STATUS_CODE` is used only for sites with no exposure row. See [source 02](02-epa-human-exposure.md#active-sites). So `SELECT count(*) FROM npl_site_boundaries` returns ~1,908, while the user-visible count is `SELECT count(*) FROM active_npl_sites` (1,337 with the 2026-10-10 feed: 1,322 with a boundary plus 15 without).

## Fields used

`EPA_ID`, `SITE_NAME`, `STATE_CODE`, `NPL_STATUS_CODE`, `URL_ALIAS_TXT`, and `geometry`. `EPA_ID` and `STATE_CODE` are uppercased on load.

## Load mechanics

`NplBoundaryLoader.loadIntoStaging(url)`:
1. Truncates `npl_site_boundaries_staging`.
2. Streams the GeoJSON `features`, upserting each into staging. Rows with null `EPA_ID` or null geometry are skipped. Geometry goes in as `ST_SetSRID(ST_GeomFromGeoJSON(...), 4326)`.
3. Returns the row count.

Then `DataBootstrapService` validates and swaps:
- Abort if staging count is 0.
- Abort if the table already had data and staging is below 90% of the live count.
- `NplBoundaryRepository.swapStagingToLive()` runs `TRUNCATE site_seo_page_cache, state_page_cache, npl_human_exposure, npl_site_boundaries` then `INSERT ... SELECT` from staging, in a transaction. The dependent tables no longer carry FKs to `npl_site_boundaries(epa_id)` (dropped in V5); they are still truncated here and rebuilt by the exposure reload that follows.

The GIST index `idx_npl_boundaries_geom` is defined in migration V1 and persists across the swap (the table is truncated, not dropped).

A valid Final-site spot-check ID is `GAD990855074` (Firestone Tire & Rubber Co., Albany Plant, GA). Do not reuse older notes' `GA8213820731`; that ID is not present in the EPA data.

## Refresh

Follow the standard procedure in the [README](../README.md). Per-source specifics: place the extracted `.gdb` at `/Users/manojbade/workspace/Info/superfund/superfund_audit_data/NPL_Boundaries/NPL_Boundaries.gdb` (or update `GDB_PATH`/`GDB_PATH_ALT` in the script), then run the converter.

## Quirks

- `pyogrio` prints an `organizePolygons() received a polygon with more than 100 parts` warning for a few complex sites. Harmless.
- The raw GDB is not committed and not on prod. Prod only ever loads the GitHub release GeoJSON.
- The converted GeoJSON (`scripts/npl_site_boundaries.geojson`) is gitignored.
