# 01: EPA NPL Superfund Site Boundaries

Polygon boundaries for EPA National Priorities List (Superfund) sites. This is the spatial backbone of Site Plain: it answers "which Superfund sites are near this address" and supplies the site name, state and EPA profile link for sites the exposure feed does not cover.

## Upstream

- Catalog entry (EPA EDG, linked from EPA's Superfund data page and from the About page): `https://edg.epa.gov/metadata/catalog/search/resource/details.page?uuid=%7BFC07D75C-8596-434B-B1A6-0688C9CD45B5%7D`. It redirects to the data.gov record (slug `npl-superfund-site-boundaries-epa-9a10b` on 2026-10-11). The older slugs `npl-superfund-site-boundaries-epa` and `...-epa10` now 404; use the EDG link.
- Direct GDB download: `https://edg.epa.gov/data/PUBLIC/OLEM/OLEM-OSRTI/NPL_Boundaries.zip` (about 21 MB; the zip's root is `NPL_Boundaries.gdb/`).
- Hosted feature service (not used): `https://services.arcgis.com/cJ9YHowT8TU7DUyn/arcgis/rest/services/FAC_Superfund_Site_Boundaries_EPA_Public/FeatureServer`.
- Cadence: EPA's SEGS harvests regional data weekly, but the set of sites and their boundaries changes rarely. Refresh roughly yearly.
- Provenance: the zip is unversioned at that URL. Record its `Last-Modified` in the release notes (July release: 2026-07-11).
- The 2026-10-10 zip is broken (5.35% blank `NPL_STATUS_CODE`, 11.35% blank `STATE_CODE`) and fails the converter's checks. It was still the file at that URL on 2026-10-11.

## Why a Python conversion step

The raw file is an ESRI File Geodatabase. The Java app has no GeoTools and cannot read it, so `scripts/convert_npl_to_geojson.py` (geopandas and pyogrio, pinned in `scripts/requirements.txt`; setup in the [README](../README.md#41-converter-setup-any-machine)) reads the `SITE_BOUNDARIES_SF` layer and writes one GeoJSON feature per site in EPSG:4326. The GeoJSON is published as a GitHub release asset, and the app loads that. The GDB has other layers (`OU_BOUNDARIES_SF`, `IC_BOUNDARIES_SF`, `SITE_FEATURE_*`); they are not used.

## Conversion

In order:

1. **Input checks** on the raw layer: more than 0.5% of rows with a blank `EPA_ID`, `NPL_STATUS_CODE` or `STATE_CODE` fails (`--max-null-rate`).
2. Reproject to EPSG:4326 if needed; uppercase `EPA_ID`, `STATE_CODE` and `NPL_STATUS_CODE`; drop rows with no `EPA_ID` or no geometry.
3. **Drop state source-control areas**: rows whose `SITE_FEATURE_NAME` contains "Source Control". These are drainage basins mapped for upland source control, filed under the site's top feature type but not its footprint: WA0002329803 "LDW Uplands Source Control Area" (WA Department of Ecology, 80.6 km2 against the 1.8 km2 waterway), ORSFN1002155 "Portland Harbor Uplands ODEQ Source Control" (Oregon DEQ, 32.6 km2), and WAD980726368 "CBNSTF OU 5 Source Control Area" (never the winning type, so no change). Unioning the first two put homes miles from the Lower Duwamish "inside" the site. A site left with no rows after the drop fails the run.
4. **Pick the top feature type** per site. Priority, highest first: Comprehensive Site Area, Total Site Polygon/OU Aggregation, Site Boundary, Current Ground Boundary, OU Boundary Aggregation, Extent of Contamination, Contamination Boundary (Groundwater), Contamination Boundary, Other. Null or unmapped types (`<Null>`, `Waste in Place`, `OU Boundary` and similar) rank last.
5. **Union ties.** When several rows tie at the top type, their geometries are unioned into one MultiPolygon, so a lookup measures distance to the whole designated site area, not one arbitrary piece. Example: ORSFN1002155 Portland Harbor has 99 Comprehensive Site Area polygons after the drop; the union is about 18.2 km2 (EPA's 8.5 km2 in-water area plus the upland cleanup properties filed with it), where the old one-row pick kept a 0.013 km2 fragment. Lower Duwamish is about 5.2 km2. The July 2026 GDB has 19 tied sites (18 Final).
6. **Attributes** come from one row of the winning group, chosen deterministically: non-null `NPL_STATUS_CODE` first, then non-null `STATE_CODE`, then the latest `LAST_CHANGE_DATE`, then the lowest GDB feature id. Output is the same whatever the input row order.
7. **Repair**: invalid geometry is fixed with `make_valid` (24 sites in July, no area change), and every output geometry is a MultiPolygon.
8. **Union review gate** (below).
9. **Output checks**: the Final count must be within 1,200..1,450 (`--min-final`, `--max-final`) and no Final site may lack a state.

Any failed check prints `ERROR: input check failed, nothing written: <reason>` and exits 2 without writing. Self-check without a GDB: `--self-test`.

### Union review gate

A tied union is flagged when one of its pieces adds more than 2 km2 (`--union-review-km2`) and grows the union of the other pieces by more than 1.5 times. Areas are measured in EPSG:6933 (equal area). Every flagged site must be listed in `REVIEWED_UNIONS` in the script, with the largest union area accepted after checking EPA's site description, or the run fails. A reviewed site whose union grows past its accepted area fails too, so a new oversized piece in a later release is caught. In July six sites are flagged and reviewed: Eielson AFB (80.6 km2), Eastern Michaud Flats (9.4), Black Butte Mine (5.9), Portland Harbor (18.2), Midnite Mine (8.8) and Moses Lake Wellfield (49.9). To accept a new one, read EPA's site description, add the EPA ID with an area a little above the printed union and a one-line reason, and commit the change.

Reference, July 2026 GDB: 2,114 raw features, 3 source-control rows dropped, 1,908 unique sites, 1,328 Final. The output (`f979af8e...7c13`, 58,474,396 bytes) is the candidate for the next release; the live release `data-2026-07-12` (`3914ed30...7e48`, 57,800,609 bytes) was built before the union, drop and repair steps.

## Status is decided at query time

The converter and the loader keep every status (about 1,908 rows: F, D, N, P, R, A, S). Whether a site is active is decided by the `active_npl_sites` view (migration V5): EPA's exposure feed `nplstatus` wins, and this file's `NPL_STATUS_CODE` counts only for sites with no feed row. See [source 02](02-epa-human-exposure.md#active-sites). So `SELECT count(*) FROM npl_site_boundaries` returns about 1,908, while users see `SELECT count(*) FROM active_npl_sites` (1,337 with the 2026-10-10 feed: 1,322 with a boundary plus 15 without).

## Fields used

`EPA_ID`, `SITE_NAME`, `STATE_CODE`, `NPL_STATUS_CODE`, `URL_ALIAS_TXT` and the geometry. `EPA_ID` and `STATE_CODE` are uppercased on load. `URL_ALIAS_TXT` is kept only when it is a `.gov` URL (a missing `https://` is added) and does not end in a placeholder such as `/null`; otherwise the page links to EPA's site search instead.

## Load mechanics

`SourceDownloader` fetches `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` to a temp file (redirects followed; 300 s for the whole download and a 240 MB cap by default; non-2xx, `text/html`, empty or non-JSON bodies rejected) and computes its SHA-256.

`NplBoundaryLoader.loadIntoStaging(file)`:

1. Truncates `npl_site_boundaries_staging`.
2. Streams the GeoJSON one feature at a time; each geometry is copied from the parser as text, never built as a tree, so a full reload fits in a 48 MB heap. Each feature is inserted with a plain `INSERT` as `ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(...), 4326))`, so a Polygon is stored as a MultiPolygon.
3. Counts what it saw, and `DataBootstrapService` rejects the file, keeping the live boundaries, on any of the checks in the [README](../README.md#25-validation-gates): not a `FeatureCollection`; a `crs` other than CRS84 or EPSG:4326; any of `EPA_ID`, `SITE_NAME`, `STATE_CODE`, `NPL_STATUS_CODE` missing on more than 0.5% of features; a repeated `EPA_ID`; a geometry that is not a well-formed Polygon or MultiPolygon; a coordinate outside longitude -180..180 or latitude -90..90; a Final count outside 1,200..1,450 or a Final feature with no state; fewer than 90% of the live row count staged; fewer than 90% of the projected active sites with both a boundary and a feed row; or an active site count more than 5% away from the live one (override: `SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT`). The message lists every failed check with counts and up to five example IDs.

The swap (`NplBoundaryRepository.replaceLiveFromStaging`) runs inside the single refresh transaction with the exposure swap and both page cache rebuilds: `DELETE FROM npl_site_boundaries`, then `INSERT ... SELECT` from staging. Readers keep the old rows until commit. After commit the table is analyzed and staging is emptied. The URL, SHA-256, size, `Last-Modified` and counts go into `data_source_state` in the same transaction.

Indexes persist across the swap: `idx_npl_boundaries_geom` (V1, GIST on `geom`) and `idx_npl_boundaries_geog` (V6, GIST on `geom::geography`). The lookup prefilters with a latitude-scaled bounding box on `geom`, so it runs on the geometry index, and decides with an exact geography `ST_DWithin` (3 miles); the geography index is the planner's fallback.

A valid Final spot-check ID is `GAD990855074` (Firestone Tire & Rubber Co., Albany Plant, GA). Do not reuse older notes' `GA8213820731`; it is not in EPA's data.

## Refresh

Follow [Publish and load a new boundary release](../README.md#42-publish-and-load-a-new-boundary-release). Rollback and recovery are in the same README.

## Quirks

- pyogrio prints `organizePolygons() received a polygon with more than 100 parts` for a few complex sites. Harmless.
- The raw GDB is not committed and not on prod. Prod only loads the GitHub release GeoJSON.
- The converted GeoJSON (`scripts/npl_site_boundaries.geojson`) is gitignored.
