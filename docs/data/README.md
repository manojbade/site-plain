# Site Plain Data Documentation

Every dataset the app loads, where it comes from, how it gets into the database, and how to refresh it. One doc per source lives in [sources/](sources/). This folder is committed to the repository; keep it current when data handling changes.

Last verified: July 12, 2026 (NPL boundaries refreshed to EPA 2026-07-11 release).

---

## Source Inventory

| # | Dataset | Upstream | Format | Refresh | Loader | Table(s) |
|---|---------|----------|--------|---------|--------|----------|
| [01](sources/01-epa-npl-boundaries.md) | NPL Superfund site boundaries | EPA EDG (`NPL_Boundaries.zip`), via GitHub release | GeoJSON (converted from GDB) | Manual, when EPA layer changes | `NplBoundaryLoader` | `npl_site_boundaries` |
| [02](sources/02-epa-human-exposure.md) | Human exposure status | EPA SEMS (`Human_Exposure_Site_List.json`) | JSON | Automatic, every startup | `HumanExposureLoader` | `npl_human_exposure` |
| [03](sources/03-census-geocoder.md) | Address to lat/lon | US Census Bureau geocoder | REST API | Live per request, no load | `GeocodingService` | none |
| [04](sources/04-nominatim-geocoder.md) | Address to lat/lon (fallback) | OpenStreetMap Nominatim | REST API | Live per request, no load | `GeocodingService` | none |

Derived tables rebuilt on every startup: `site_seo_page_cache`, `state_page_cache`. In-memory sitemap XML is also rebuilt.

---

## How Loading Works

`DataBootstrapService` runs once at startup (`ApplicationRunner`). It does two things in order:

1. **Boundaries** (`refreshBoundariesIfNeeded`): reloads `npl_site_boundaries` only when the refresh policy says so (see below). Reads the GeoJSON from `SITEPLAIN_DATA_NPL_BOUNDARIES_URL`, loads into a staging table, validates, then swaps into live.
2. **Human exposure + caches** (`refreshHumanExposureAndCaches`): always runs. Reloads `npl_human_exposure` fresh from EPA, then rebuilds `site_seo_page_cache`, `state_page_cache`, and the sitemap.

Railway containers are ephemeral. Nothing on the container filesystem persists across deploys; the Postgres database is what persists. Human exposure reloads on every deploy because it is cheap and EPA updates it continuously. Boundaries reload only when triggered.

### Refresh policy (`DataRefreshPolicyService`)

Boundaries reload if ANY of these is true:

- The `npl_site_boundaries` table is empty (first boot), OR
- `SITEPLAIN_DATA_REFRESH_ON_STARTUP=true`, OR
- The last load is older than `SITEPLAIN_DATA_MAX_AGE_DAYS` (default 90).

Note the 90-day trigger: unlike some sibling projects, this app still auto-reloads boundaries after 90 days. If you only want to change the source URL, that auto-trigger may fire on its own, so always keep `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` pointing at a valid current release.

### Railway variables that control loading

| Variable | Steady-state value | Purpose |
|---|---|---|
| `SITEPLAIN_DATA_BOOTSTRAP_ENABLED` | `true` | Master switch. `false` skips all loading (human exposure included). Leave `true`. |
| `SITEPLAIN_DATA_REFRESH_ON_STARTUP` | `false` | Flip to `true` for exactly one deploy to force a boundary reload. |
| `SITEPLAIN_DATA_MAX_AGE_DAYS` | `90` | Age after which boundaries auto-reload. |
| `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` | GitHub release asset URL | Where the boundary GeoJSON is fetched from. |
| `SITEPLAIN_DATA_HUMAN_EXPOSURE_URL` | EPA SEMS JSON URL | Live human exposure feed. |

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
   Expect `Final NPL sites (status='F'): ~1,328`. The file itself holds ~1,908 sites (all statuses); the app filters to Final at query time. A large drop means something is wrong; do not proceed.
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
5. Point Railway at the new asset and force one reload:
   ```bash
   railway variables --set SITEPLAIN_DATA_NPL_BOUNDARIES_URL=<new asset URL> --skip-deploys
   railway variables --set SITEPLAIN_DATA_REFRESH_ON_STARTUP=true --skip-deploys
   ```
6. Build and deploy:
   ```bash
   mvn clean package -DskipTests
   railway up
   ```
7. Watch logs for `NPL boundaries loaded into staging: N rows` and `Bootstrap complete. Sites: X, Exposure records: Y, ...`.
8. Reset the one-shot flag so future restarts do not re-download:
   ```bash
   railway variables --set SITEPLAIN_DATA_REFRESH_ON_STARTUP=false --skip-deploys
   ```
9. Run the verification queries below and the live-site checks.

The GeoJSON is gitignored (`scripts/npl_site_boundaries.geojson`); the GitHub release is the source of truth for prod, not the repo.

---

## Post-Refresh Verification

```sql
-- Table holds all statuses (~1,908). This is expected; the app filters to Final at query time.
SELECT count(*) FROM npl_site_boundaries;                       -- ~1,908
SELECT count(*) FROM npl_site_boundaries WHERE npl_status_code = 'F';  -- ~1,328 (user-visible)

SELECT npl_status_code, count(*) FROM npl_site_boundaries GROUP BY 1 ORDER BY 2 DESC;

-- Human exposure reloads every deploy; must be well over 1,500 or bootstrap aborts.
SELECT count(*) FROM npl_human_exposure;
SELECT humexposurestscode, count(*) FROM npl_human_exposure GROUP BY 1 ORDER BY 2 DESC;

-- Derived caches (one row per Final site / per state with a Final site).
SELECT count(*) FROM site_seo_page_cache;                       -- ~1,328
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
curl -s "https://site-plain.com/actuator/health"                                   # {"status":"UP"}
```

---

## Failure Behavior

- **Boundary reload on a populated table:** if the download yields zero rows, or fewer than 90% of the current live count, the load aborts with an exception and the swap does not happen. The previous data stays live. Check deploy logs.
- **Human exposure reload:** if the feed returns 1,500 or fewer matched rows, bootstrap throws. On a first boot (empty DB) this aborts startup; the app will not come up with no data.
- **Boundaries are never touched by a routine deploy** unless the refresh policy triggers (flag set, table empty, or older than `MAX_AGE_DAYS`).
- **A boundary reload against a dead URL fails startup, not just the refresh.** When the policy triggers a reload and `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` 404s or is unreachable, `NplBoundaryLoader` throws and `DataBootstrapService` propagates it, so the container fails to boot. The dangerous case is silent: with `MAX_AGE_DAYS=90` and `REFRESH_ON_STARTUP=false`, everything looks fine for 90 days, then the next restart past that age tries to reload and takes the site down if the release URL has since been deleted or renamed. Keep the URL valid for as long as it is referenced, or delete old releases only after pointing the var at a new one.

---

## Fragile Points

| Risk | Mitigation |
|------|-----------|
| EPA renames the GDB layer (currently `SITE_BOUNDARIES_SF`) | `convert_npl_to_geojson.py` fails at read. Update `LAYER` in the script. Confirm with `python3 -c "import pyogrio; print(pyogrio.list_layers('.../NPL_Boundaries.gdb'))"`. |
| EPA changes human exposure JSON field names | `HumanExposureLoader` silently loads fewer rows; the >1,500 guard catches a total failure but not a partial one. Check `humexposurestscode` distribution after a deploy. |
| data.gov dataset slug changes | The dataset moved from `npl-superfund-site-boundaries-epa10` to `npl-superfund-site-boundaries-epa`. Find the current GDB link under the "Zipped File Geodatabase" resource. |
| GitHub release URL changes | Update `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` in Railway. |
| PostGIS missing on a fresh Railway Postgres | `CREATE EXTENSION IF NOT EXISTS postgis` is in V1; the DB image must support PostGIS. |
| 90-day auto-reload fires unexpectedly | It reloads from whatever `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` points at, so keep that URL valid at all times. |
