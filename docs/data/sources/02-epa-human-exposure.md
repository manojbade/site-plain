# 02: EPA Human Exposure Site List

The user-facing status signal. For each Superfund site, EPA reports whether human exposure is under control. This drives the Red / Yellow / Green / Gray badge on every result.

## Upstream

- URL: `https://www3.epa.gov/semsjson/Human_Exposure_Site_List.json` (~1.3 MB)
- Configured via `SITEPLAIN_DATA_HUMAN_EXPOSURE_URL` (this is also the built-in default)
- Cadence: continuous. EPA updates exposure assessments as cleanups progress, so this feed changes far more often than the boundaries.
- No credentials, no archiving. It is small and reloads every deploy.

## Load mechanics

`HumanExposureLoader.loadIntoStaging(url)`:
1. Truncates `npl_human_exposure_staging`.
2. Builds the set of live `EPA_ID`s from `npl_site_boundaries`.
3. Reads the JSON `data` array. For each row, uppercases `epaid`; **skips any row whose `epaid` is not a currently loaded boundary**. This is why exposure must load after boundaries, and why a boundary reload truncates exposure (the swap rebuilds it against the new site set).
4. Upserts matched rows into staging.

Fields read: `epaid`, `humexposurestscode`, `humanexposurepathdesc`, `nplstatus`, `sitename`. Join key is `epaid` (JSON, lowercase) to `EPA_ID` (boundaries, uppercase), both normalized to uppercase.

Guard: `DataBootstrapService` throws if fewer than 1,501 rows matched. On an empty database this aborts startup.

## Exposure status codes

| Code | Meaning | UI label | Color |
|------|---------|----------|-------|
| HENC | Human exposure NOT under control | Not under control | Red |
| HEUC | Human exposure under control | Under control | Yellow |
| HEPR | Under control, protective remedies in place | Protective remedies in place | Yellow |
| HHPA | Long-term health protection achieved | Health protection achieved | Green |
| HEID | Insufficient data | Insufficient data | Gray |
| null / missing / no record | Not reported | Not reported | Gray |

The exact mapping lives in `ExposureStatusMapper`. Red/yellow/green reflects EPA's exposure-control assessment, not whether a site is actively dangerous. Sites with a Final boundary but no exposure record render Gray ("Not reported").

## Tables

| Table | Purpose |
|-------|---------|
| `npl_human_exposure` | Live records, FK to `npl_site_boundaries(epa_id)` |
| `npl_human_exposure_staging` | Staging, verified before swap |

## Refresh

None. This reloads automatically on every deploy as part of `refreshHumanExposureAndCaches`, which also rebuilds `site_seo_page_cache`, `state_page_cache`, and the sitemap. To pick up the latest exposure status, just redeploy.

## Quirks

- Because loading filters to known boundary IDs, exposure counts move a little whenever the boundary set changes.
- A partial upstream schema change (one renamed field) would not trip the >1,500 guard. After a deploy, sanity-check the `humexposurestscode` distribution if anything looks off.
