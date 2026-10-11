# 02: EPA Human Exposure Site List

The user-facing status signal. For each Superfund site, EPA reports whether human exposure is under control. This drives the Red / Yellow / Green / Gray badge on every result.

## Upstream

- URL: `https://www3.epa.gov/semsjson/Human_Exposure_Site_List.json` (~1.3 MB)
- Configured via `SITEPLAIN_DATA_HUMAN_EXPOSURE_URL` (this is also the built-in default)
- Cadence: continuous. EPA updates exposure assessments as cleanups progress, so this feed changes far more often than the boundaries.
- No credentials, no archiving. It is small and is downloaded on every deploy and once a day (08:30 UTC by default); it is swapped in only when its SHA-256 differs from the live feed's.

## Load mechanics

`SourceDownloader` fetches the feed to a temp file (60 s whole-download timeout and 6 MB cap by default; non-2xx, `text/html`, empty or non-JSON bodies rejected), records its SHA-256 and `Last-Modified`, and the refresh stops here, keeping the live rows, when the SHA-256 and URL match the live feed in `data_source_state` and the live rows still match that record (the same row count, none written after its `loaded_at`). If anything else rewrote `npl_human_exposure` (an older app version after a rollback, manual SQL), the unchanged feed is staged and swapped in again, with a WARN.

`HumanExposureLoader.loadIntoStaging(file)`:
1. Truncates `npl_human_exposure_staging`.
2. Streams the JSON `data` array one row at a time. For each row, uppercases `epaid` and skips rows with no `epaid`.
3. Inserts **every** other row into staging, including sites that have no boundary in `npl_site_boundaries` (for example Final sites EPA has not published a polygon for, such as WA2170023418 Puget Sound Naval Shipyard).

Fields read: `epaid`, `humexposurestscode`, `humanexposurepathdesc`, `nplstatus`, `sitename`, `state_code`, `friendlyurl`. Join key is `epaid` (JSON, lowercase) to `EPA_ID` (boundaries, uppercase), both normalized to uppercase.

- `state_code` is kept when it is a known state or territory code (`StateNames`). Otherwise the first two letters of the EPA ID are used if they are a known code, else the state is left null. On the 2026-10-10 feed every row has a valid `state_code`.
- `friendlyurl` has no scheme in the feed (`www.epa.gov/superfund/...`), so `https://` is added. A scheme-less URL on another host is dropped, and the page falls back to the EPA search link.

Guards: before the swap, the feed is rejected (and the live rows kept) when it says `success: false`; `meta.total_data` differs from the number of rows; `epaid`, `nplstatus`, `sitename` or a usable state is missing on more than 0.5% of rows, or `humexposurestscode` on more than 2% (3 of 1,905 on 2026-10-10); an `epaid` repeats; it has 1,500 or fewer rows with an `epaid` (1,905 on 2026-10-10); any non-null `nplstatus` is outside `F, D, P, N, R, S`; the `F` row count is outside 1,200..1,450 (1,337 on 2026-10-10); fewer than 90% of the projected active sites would have both a boundary and a feed row (the feed's `epaid` values do not match the boundary EPA_IDs, for example if EPA switched to the numeric SEMS id; 1,322 of 1,337 on 2026-10-10); or the active site count would move more than 5% either way from the current one (a non-matching feed adds unmatched sites from both sides, so the count rises). Because `nplstatus` decides which sites are active, a vocabulary change such as `Final` for `F` would otherwise empty the site. A `humexposurestscode` with no label is logged at WARN, not rejected. The swap itself runs in the single refresh transaction (see the [README](../README.md#how-loading-works)).

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

HEIC appears in the feed (2 rows on 2026-10-10, both non-Final sites) but EPA's published definitions do not cover it, so it is shown as insufficient data rather than as a raw code. Each site page, results page and results PDF shows "EPA exposure status as of <date>": the HTTP `Last-Modified` of the live feed as recorded in `data_source_state`, or, when none was recorded, when the live rows were loaded (max `loaded_at` of `npl_human_exposure`). Either is shown as a calendar date in America/New_York, since EPA publishes on Eastern time (an evening update is after midnight UTC).

The exact mapping lives in `ExposureStatusMapper`. Red/yellow/green reflects EPA's exposure-control assessment, not whether a site is actively dangerous. Sites with a Final boundary but no exposure record render Gray ("Not reported").

## Tables

| Table | Purpose |
|-------|---------|
| `npl_human_exposure` | Live records, one per feed site. No FK to `npl_site_boundaries` since V5, so sites without a boundary can be stored |
| `npl_human_exposure_staging` | Staging, validated before the swap and emptied after it |

## Refresh

Automatic. The feed is checked on every deploy and by a daily scheduled refresh (`SITEPLAIN_DATA_SCHEDULED_REFRESH_CRON`, default `0 30 8 * * *` UTC), and swapped in only when it changed, together with `site_seo_page_cache` and `state_page_cache` in one transaction as on startup; the sitemap is rebuilt after each run. An unchanged feed only updates `data_source_state.last_checked_at` (`last_status = UNCHANGED`). At startup the caches and sitemap are rebuilt even when the feed did not change. If EPA is down or the feed is rejected, the live rows stay, the app keeps serving, and `data_source_state.last_error` says why; the next run tries again. To pick it up sooner, restart the service.

## Quirks

- A renamed `humanexposurepathdesc` or `friendlyurl` would not trip the guards (both are optional). After a deploy, sanity-check the `humexposurestscode` distribution if anything looks off.
