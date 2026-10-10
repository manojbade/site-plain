# Site Plain Data Documentation

Every dataset the app loads, where it comes from, how it gets into the database, and how to refresh it. One doc per source lives in [sources/](sources/). This folder is committed to the repository; keep it current when data handling changes.

Last verified: July 12, 2026 (NPL boundaries refreshed to EPA 2026-07-11 release).

---

## Source Inventory

| # | Dataset | Upstream | Format | Refresh | Loader | Table(s) |
|---|---------|----------|--------|---------|--------|----------|
| [01](sources/01-epa-npl-boundaries.md) | NPL Superfund site boundaries | EPA EDG (`NPL_Boundaries.zip`), via GitHub release | GeoJSON (converted from GDB) | Manual, when EPA layer changes | `NplBoundaryLoader` | `npl_site_boundaries` |
| [02](sources/02-epa-human-exposure.md) | Human exposure status | EPA SEMS (`Human_Exposure_Site_List.json`) | JSON | Checked every startup and daily (08:30 UTC), swapped in when the file changed | `HumanExposureLoader` | `npl_human_exposure` |
| [03](sources/03-census-geocoder.md) | Address to lat/lon | US Census Bureau geocoder | REST API | Live per request, no load | `GeocodingService` | none |
| [04](sources/04-nominatim-geocoder.md) | Address to lat/lon (fallback) | OpenStreetMap Nominatim | REST API | Live per request, no load | `GeocodingService` | none |

Derived tables rebuilt on every startup and whenever a source changes: `site_seo_page_cache`, `state_page_cache`. In-memory sitemap XML is also rebuilt.

---

## How Loading Works

`DataBootstrapService` runs at startup (`ApplicationRunner`) and once a day (see below), holding a Postgres advisory lock (`DataRefreshLock`) so two instances (Railway runs the new deploy while the old one still serves) never refresh at once; an instance that cannot take the lock skips and serves the data already live.

1. **Stage and validate.** Boundaries are downloaded and loaded into `npl_site_boundaries_staging` only when the refresh policy says so (see below). The exposure feed is downloaded every time; when its SHA-256 matches the live feed it is not staged again. Each download (`SourceDownloader`) streams to a temp file with a timeout, a size cap and a JSON sniff, and each loader streams the file one record at a time and checks its content (see the source docs).
2. **Swap in one transaction.** Boundaries (if reloaded), exposure (if changed), `site_seo_page_cache` and `state_page_cache` are replaced together with `DELETE` plus `INSERT ... SELECT`, so readers see the old rows until commit and never an empty or half-built table. At startup the two caches are rebuilt even when no source changed. The source identity rows in `data_source_state` are written in the same transaction.
3. **After commit:** `ANALYZE` the replaced tables, empty both staging tables, rebuild the in-memory sitemap.

**Daily scheduled refresh (`ScheduledDataRefresh`).** Once a day (`SITEPLAIN_DATA_SCHEDULED_REFRESH_CRON`, Spring cron in UTC, default `0 30 8 * * *`) the same refresh runs again with the same limits, checks, lock and swap. It is not a startup run, so `SITEPLAIN_DATA_REFRESH_ON_STARTUP` is ignored and nothing is swapped when the feed is unchanged (`last_status = UNCHANGED`). Boundaries reload on this path only when the configured URL still differs from the live source, which happens only after a boundary reload failed at startup; that reload is retried daily. The job skips until the app is ready, so it never overlaps the startup refresh, and the advisory lock keeps it apart from another instance. After each run it rebuilds the in-memory sitemap from the caches, so an instance whose own startup refresh was skipped on the lock catches up. It is not created when `SITEPLAIN_DATA_BOOTSTRAP_ENABLED=false`; a cron of `-` turns it off. Logs: `Scheduled data refresh starting`, then the usual refresh lines.

### Health and readiness

- `/actuator/health/readiness` (`readinessState` plus `db`): `OUT_OF_SERVICE` (503) until the startup refresh has returned, then `UP` (200). The refresh returns once the new data is live, or once a failed source has been logged and the app is serving the existing data. A first boot that fails (empty database) never turns ready, because the app exits.
- `/actuator/health/liveness`: `UP` once the context has started.
- `/actuator/health`: unchanged. `UP` during the startup refresh too, `DOWN` when the database is down. The body now also lists the two groups.
- Only the health endpoint is exposed over HTTP (`management.endpoints.web.exposure.include=health`, details hidden). The two probe paths answer on any host, so the canonical-host redirect does not apply to them; every other path, `/actuator/health` included, still redirects to `https://site-plain.com`.

**Railway setting (owner action):** set the service healthcheck path to `/actuator/health/readiness`. Railway then keeps the old deployment serving until the new container reports ready, and a deploy that never turns ready (a failed first boot, or a refresh that outlasts the healthcheck timeout) is not switched in. Railway's default healthcheck timeout is 300 s. A routine deploy is ready within seconds of starting (about 1 s of refresh in the drills), and at most about 60 s later when the EPA feed hangs (its download timeout). A boundary reload adds the download plus about 3 s of staging, but a hung boundary download holds readiness down for the full 300 s download timeout, so the deploy would be cut off at the default healthcheck timeout (safely: the old deployment keeps serving). Set the healthcheck timeout to 600 s (service setting, or `RAILWAY_HEALTHCHECK_TIMEOUT_SEC=600`) so a slow but working download can finish.

Railway containers are ephemeral. Nothing on the container filesystem persists across deploys; the Postgres database is what persists.

### Refresh policy (`DataRefreshPolicyService`)

Boundaries reload only when ONE of these is true:

- The `npl_site_boundaries` table is empty (first boot), OR
- `SITEPLAIN_DATA_REFRESH_ON_STARTUP=true`, OR
- `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` differs from the `source_url` recorded for the live boundaries in `data_source_state`.

There is no age trigger: `SITEPLAIN_DATA_MAX_AGE_DAYS` is ignored (a warning is logged when it is set). When boundaries are live but `data_source_state` has no boundary row (the first boot after migration V7), the configured URL is adopted as their source without reloading (`last_status = ADOPTED`, no SHA-256 until the next real load).

Adoption trusts the configured URL: it cannot tell which file the live rows came from. So do not change `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` in the same deploy that first applies V7. If you do, the new URL is recorded as the source of the old rows and never loaded (seen in a drill: the July rows were labeled with the candidate release URL, and Portland Harbor kept its July area). Change the URL in a later deploy, or set `SITEPLAIN_DATA_REFRESH_ON_STARTUP=true` for that one boot.

```sql
-- What is live, and how the last refresh attempt went.
SELECT dataset, source_url, sha256, bytes, last_modified, row_count, final_count,
       loaded_at, last_checked_at, last_status, last_error
FROM data_source_state;
```

### Railway variables that control loading

| Variable | Steady-state value | Purpose |
|---|---|---|
| `SITEPLAIN_DATA_BOOTSTRAP_ENABLED` | `true` | Master switch. `false` skips all loading (human exposure included). Leave `true`. |
| `SITEPLAIN_DATA_REFRESH_ON_STARTUP` | `false` | `true` reloads boundaries on every boot. Only needed to re-download the same URL. |
| `SITEPLAIN_DATA_MAX_AGE_DAYS` | unset | Ignored (the age trigger is retired). Safe to delete. |
| `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` | GitHub release asset URL | Where the boundary GeoJSON is fetched from. Changing it triggers one reload. |
| `SITEPLAIN_DATA_HUMAN_EXPOSURE_URL` | EPA SEMS JSON URL | Live human exposure feed. |
| `SITEPLAIN_DATA_BOUNDARIES_DOWNLOAD_TIMEOUT` / `_MAX_SIZE` | `300s` / `240MB` | Whole-download limit and size cap for the boundary file. |
| `SITEPLAIN_DATA_HUMAN_EXPOSURE_DOWNLOAD_TIMEOUT` / `_MAX_SIZE` | `60s` / `6MB` | Same for the exposure feed. |
| `SITEPLAIN_DATA_SCHEDULED_REFRESH_CRON` | unset (`0 30 8 * * *`) | When the daily refresh runs, Spring cron in UTC. `-` turns it off. |

---

## Boundary Refresh Procedure (Manual)

EPA refreshes the hosted feature service weekly, but the actual set of NPL sites and their boundaries changes rarely. Do this when you want to pull the latest EPA boundaries (roughly annually is enough; check `metadata_modified` on data.gov if unsure). Full per-source detail is in [source 01](sources/01-epa-npl-boundaries.md).

1. Download the current GDB zip and extract it:
   ```bash
   curl -sO https://edg.epa.gov/data/PUBLIC/OLEM/OLEM-OSRTI/NPL_Boundaries.zip
   unzip -o NPL_Boundaries.zip -d ~/workspace/Info/superfund/superfund_audit_data/NPL_Boundaries/
   ```
   The script expects the `.gdb` at `/Users/manojbade/workspace/Info/superfund/superfund_audit_data/NPL_Boundaries/NPL_Boundaries.gdb`.
2. Convert to GeoJSON:
   ```bash
   cd ~/workspace/site-plain
   python3 scripts/convert_npl_to_geojson.py
   ```
   Use `--gdb PATH` and `--output PATH` to point elsewhere. Expect `Final NPL sites (status='F'): ~1,328`, the dropped source-control rows, a list of tied sites that were unioned, and the reviewed oversized unions. If the union review gate fails, check the named site against EPA's site description before adding it to `REVIEWED_UNIONS`. The file itself holds ~1,908 sites (all statuses); the app decides active sites at query time. The script exits non-zero and writes nothing if the input fails its null-rate, Final-count or union review checks; do not override those flags to force a broken EPA release through.
3. (Optional but recommended) diff against the currently live release to see what changed:
   ```bash
   curl -sL -o old.geojson "$(current SITEPLAIN_DATA_NPL_BOUNDARIES_URL)"
   # compare EPA_ID sets, geometry, and properties between old.geojson and scripts/npl_site_boundaries.geojson
   ```
4. Create a dated GitHub release and upload the asset:
   ```bash
   gh release create data-YYYY-MM-DD scripts/npl_site_boundaries.geojson \
     --repo manojbade/site-plain --title "NPL boundary data YYYY-MM-DD" --notes "..."
   ```
5. Point Railway at the new asset. The changed URL alone triggers one reload on the next boot:
   ```bash
   railway variables --set SITEPLAIN_DATA_NPL_BOUNDARIES_URL=<new asset URL> --skip-deploys
   ```
6. Build and deploy:
   ```bash
   mvn clean package -DskipTests
   railway up
   ```
7. Watch logs for `NPL boundaries: RELOAD (SITEPLAIN_DATA_NPL_BOUNDARIES_URL changed from ...)`, `NPL boundaries loaded into staging: N rows, M Final` and `Data refresh done in a ... ms swap. Boundaries reloaded ...`. A rejected or unreachable file logs `NPL boundary file refresh failed; keeping the live data: <reason>` and the old boundaries stay live.
8. Run the verification queries below and the live-site checks. `data_source_state` should show the new URL and its SHA-256 with `last_status = LOADED`.

The GeoJSON is gitignored (`scripts/npl_site_boundaries.geojson`); the GitHub release is the source of truth for prod, not the repo.

---

## Post-Refresh Verification

```sql
-- Table holds all statuses (~1,908). This is expected; the app decides active sites at query time.
SELECT count(*) FROM npl_site_boundaries;                       -- ~1,908
-- User-visible active sites (exposure feed status, boundary status as fallback).
SELECT count(*), count(*) FILTER (WHERE NOT has_boundary) FROM active_npl_sites;  -- ~1,337, 15 (2026-10-10)

SELECT npl_status_code, count(*) FROM npl_site_boundaries GROUP BY 1 ORDER BY 2 DESC;

-- Every feed row (~1,905); the feed is rejected with 1,500 or fewer rows or an F count outside 1,200..1,450.
SELECT count(*) FROM npl_human_exposure;
SELECT humexposurestscode, count(*) FROM npl_human_exposure GROUP BY 1 ORDER BY 2 DESC;

-- Derived caches (one row per active site / per state with an active site).
SELECT count(*) FROM site_seo_page_cache;                       -- same as active_npl_sites
SELECT state_code, site_count FROM state_page_cache ORDER BY site_count DESC LIMIT 5;  -- NJ, CA, PA near top

-- Load recency and the GIST spatial index.
SELECT max(loaded_at) FROM npl_site_boundaries;
SELECT indexname FROM pg_indexes WHERE tablename = 'npl_site_boundaries' AND indexname = 'idx_npl_boundaries_geom';

-- Spot-check a known Final site.
SELECT epa_id, site_name, state_code FROM npl_site_boundaries WHERE epa_id = 'GAD990855074';  -- Firestone Tire & Rubber Co. (Albany Plant), GA
```

Live site:

```bash
curl -s "https://site-plain.com/site/GAD990855074" | grep -c "FIRESTONE"          # site page renders
curl -s "https://site-plain.com/api/site-search/suggestions?q=firestone" | head    # search index is fresh
curl -s "https://site-plain.com/actuator/health"                                   # {"status":"UP","groups":["liveness","readiness"]}
curl -s "https://site-plain.com/actuator/health/readiness"                         # {"status":"UP"}
```

---

## Failure Behavior

- **Nothing live changes until one transaction commits.** All downloads, staging loads and checks run first. If any of them fails, or the swap transaction itself fails, the previous boundaries, exposure rows and page caches stay live.
- **With live data, a failed source never stops the app.** A failed download (timeout, non-2xx, HTML page, empty or oversized body) or a rejected file is logged at ERROR with the reason, recorded in `data_source_state` (`last_status = FAILED`, `last_error`; the identity columns still describe the live data), and the app starts on the existing data. The other source still goes ahead if it passed, and at startup the page caches and sitemap are still rebuilt. A boundary URL that keeps failing is retried on every boot until it loads or is changed back.
- **Only an empty database aborts startup.** On a first boot with no boundaries, any failure throws from the runner and nothing is swapped.
- **Boundaries are never touched by a routine deploy** unless the URL changed, the table is empty, or `SITEPLAIN_DATA_REFRESH_ON_STARTUP=true`.
- **`SITEPLAIN_DATA_BOOTSTRAP_ENABLED=false`** skips every download, including the daily refresh, and serves what is in the database; the sitemap is still built from the caches.
- **The daily refresh follows the same rules.** A failed or rejected feed keeps the live rows, logs at ERROR and records `FAILED`; an unexpected error (the database unreachable) is logged as `Scheduled data refresh failed; keeping the live data` and the next run tries again.

## Fragile Points

| Risk | Mitigation |
|------|-----------|
| EPA renames the GDB layer (currently `SITE_BOUNDARIES_SF`) | `convert_npl_to_geojson.py` fails at read. Update `LAYER` in the script. Confirm with `python3 -c "import pyogrio; print(pyogrio.list_layers('.../NPL_Boundaries.gdb'))"`. |
| EPA changes human exposure JSON field names | The feed is rejected when `epaid`, `nplstatus`, `sitename` or a usable state is missing on more than 0.5% of rows, or `humexposurestscode` on more than 2%, and the live feed stays. New `humexposurestscode` values are only logged at WARN. |
| data.gov dataset slug changes | The dataset moved from `npl-superfund-site-boundaries-epa10` to `npl-superfund-site-boundaries-epa`. Find the current GDB link under the "Zipped File Geodatabase" resource. |
| GitHub release URL changes | Update `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` in Railway. |
| Too little heap for the boundary reload | Staging streams one feature at a time: the 57.8 MB release reloads at `-Xmx48m` (peak RSS about 230 MB) and fails at `-Xmx40m`. An `OutOfMemoryError` is not caught: the app exits during boot, live data stays as it was, and the partial staging rows are cleared by the next load. Railway's default heap is far above this; do not set a JAVA_OPTS heap below 128m. |
| PostGIS missing on a fresh Railway Postgres | `CREATE EXTENSION IF NOT EXISTS postgis` is in V1; the DB image must support PostGIS. |
