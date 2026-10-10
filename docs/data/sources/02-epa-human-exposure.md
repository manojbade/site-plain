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
2. Reads the JSON `data` array (an `http(s)://` or `file://` URL). For each row, uppercases `epaid` and skips rows with no `epaid`.
3. Upserts **every** row into staging, including sites that have no boundary in `npl_site_boundaries` (for example Final sites EPA has not published a polygon for, such as WA2170023418 Puget Sound Naval Shipyard).

Fields read: `epaid`, `humexposurestscode`, `humanexposurepathdesc`, `nplstatus`, `sitename`, `state_code`, `friendlyurl`. Join key is `epaid` (JSON, lowercase) to `EPA_ID` (boundaries, uppercase), both normalized to uppercase.

- `state_code` is kept when it is a known state or territory code (`StateNames`). Otherwise the first two letters of the EPA ID are used if they are a known code, else the state is left null. On the 2026-10-10 feed every row has a valid `state_code`.
- `friendlyurl` has no scheme in the feed (`www.epa.gov/superfund/...`), so `https://` is added. A scheme-less URL on another host is dropped, and the page falls back to the EPA search link.

Guards: `DataBootstrapService` throws, before the swap, if the feed yields 1,500 or fewer rows with an `epaid` (1,905 on 2026-10-10), if any non-null `nplstatus` is outside `F, D, P, N, R, S`, if the `F` row count is outside 1,200..1,450 (1,337 on 2026-10-10), or if the active site count would drop more than 5% below the current one. Because `nplstatus` decides which sites are active, a vocabulary change such as `Final` for `F` would otherwise empty the site.

## Active sites

`nplstatus` from this feed is the authority for whether a site is active. A site is active when `COALESCE(npl_human_exposure.npl_status, npl_site_boundaries.npl_status_code) = 'F'`, so the boundary file's status is only a fallback for sites with no exposure row. This is defined once, in the `active_npl_sites` view (migration V5), and every reader uses it: the address lookup, site and state page caches, search, the sitemap and the active-site counts. Example: the six sites EPA deleted on 2026-08-20 (FLD032544587, NYD981486954, ILD980823991, KYD045738291, FLD076027820, GAD990741092) are `F` in the July boundary file but `D` in the feed, so they are not shown.

Active sites with no boundary (`has_boundary = false`, 15 on 2026-10-10) get a `/site/{epa_id}` page with a "no published boundary" notice, appear in search, the sitemap and a separate group on state pages, and are named on result pages for the address's state. They never appear in distance results, and no coordinates are invented for them.

## Exposure status codes

| Code | Meaning | UI label | Color |
|------|---------|----------|-------|
| HENC | Human exposure NOT under control | Not under control | Red |
| HEUC | Human exposure under control | Under control | Yellow |
| HEPR | Under control, protective remedies in place | Protective remedies in place | Yellow |
| HHPA | Long-term health protection achieved | Health protection achieved | Green |
| HEID | Insufficient data | Insufficient data | Gray |
| HEIC | No EPA definition found; shown as insufficient data | Insufficient data | Gray |
| null / missing / no record | Not reported | Not reported | Gray |

HEIC appears in the feed (2 rows on 2026-10-10, both non-Final sites) but EPA's published definitions do not cover it, so it is shown as insufficient data rather than as a raw code. Each site page, results page and results PDF shows "EPA exposure status as of <date>", the date the feed in use was loaded (max `loaded_at` of `npl_human_exposure`).

The exact mapping lives in `ExposureStatusMapper`. Red/yellow/green reflects EPA's exposure-control assessment, not whether a site is actively dangerous. Sites with a Final boundary but no exposure record render Gray ("Not reported").

## Tables

| Table | Purpose |
|-------|---------|
| `npl_human_exposure` | Live records, one per feed site. No FK to `npl_site_boundaries` since V5, so sites without a boundary can be stored |
| `npl_human_exposure_staging` | Staging, verified before swap |

## Refresh

None. This reloads automatically on every deploy as part of `refreshHumanExposureAndCaches`, which also rebuilds `site_seo_page_cache`, `state_page_cache`, and the sitemap. To pick up the latest exposure status, just redeploy.

## Quirks

- Exposure rows no longer depend on the boundary set, but a boundary reload still truncates `npl_human_exposure`; the reload that follows in the same bootstrap restores it.
- A partial upstream schema change (one renamed field other than `nplstatus`) would not trip the guards. After a deploy, sanity-check the `humexposurestscode` distribution if anything looks off.
