# 02: EPA Human Exposure Site List

The user-facing status signal, and the authority on which sites are active. For each Superfund site EPA reports whether human exposure is under control; that drives the red, yellow, green or gray badge on every result. The same feed carries each site's NPL status (`nplstatus`).

## Upstream

- URL: `https://www3.epa.gov/semsjson/Human_Exposure_Site_List.json` (about 1.4 MB, 1,905 rows on 2026-10-10). EPA's page for it: `https://www.epa.gov/superfund/superfund-human-exposure-dashboard`.
- Configured with `SITEPLAIN_DATA_HUMAN_EXPOSURE_URL`; that URL is also the built-in default.
- Cadence: EPA updates assessments as cleanups progress, far more often than the boundaries. The file carries an HTTP `Last-Modified`.
- No credentials, no archive. The app downloads it on every startup and once a day (08:30 UTC by default) and swaps it in only when it changed.

## Load mechanics

`SourceDownloader` fetches the feed to a temp file (60 s for the whole download and a 6 MB cap by default; non-2xx, `text/html`, empty or non-JSON bodies rejected) and records its SHA-256 and `Last-Modified`. The refresh stops there, keeping the live rows, when the SHA-256 and the URL equal those recorded for the live feed in `data_source_state` and the live rows still match that record (the same row count, none written after its `loaded_at`). If anything else rewrote `npl_human_exposure` (an older app image after a rollback, manual SQL), or the URL changed, the feed is staged and swapped in again.

`HumanExposureLoader.loadIntoStaging(file)`:

1. Truncates `npl_human_exposure_staging`.
2. Streams the `data` array one row at a time. Uppercases `epaid` and skips rows with none.
3. Inserts every other row, including sites with no boundary in `npl_site_boundaries` (Final sites EPA has published no polygon for, such as WA2170023418 Puget Sound Naval Shipyard). `loaded_at` is the database clock.

Fields read: `epaid`, `humexposurestscode`, `humanexposurepathdesc`, `nplstatus`, `sitename`, `state_code`, `friendlyurl`. The join key is `epaid` to the boundary `EPA_ID`, both uppercase.

- `state_code` is kept when it is a known state or territory code. Otherwise the first two letters of the EPA ID are used if they are a known code, else the state is null. On the 2026-10-10 feed every row has a valid `state_code`.
- `friendlyurl` has no scheme in the feed (`www.epa.gov/superfund/...`), so `https://` is added. It is dropped unless the host ends in `.gov` and the path does not end in a placeholder such as `/null`. The boundary file's URL is preferred when both exist; with neither, the page links to EPA's site search.
- `humanexposurepathdesc` is shown as text with its line breaks kept; any markup in it is escaped.

## Guards

Before the swap, the feed is rejected and the live rows kept when:

- it says `success: false`, or `meta.total_data` differs from the number of rows, or the `data` array is missing or empty;
- `epaid`, `nplstatus`, `sitename` or a usable state is missing on more than 0.5% of rows, or `humexposurestscode` on more than 2% (3 of 1,905 on 2026-10-10);
- an `epaid` repeats;
- it has 1,500 or fewer rows with an `epaid`;
- any non-null `nplstatus` is outside `F, D, P, N, R, S`;
- the `F` count is outside 1,200..1,450 (1,337 on 2026-10-10);
- fewer than 90% of the projected active sites would have both a boundary and a feed row (the feed's IDs no longer match the boundary `EPA_ID`s, for example if EPA switched to numeric SEMS IDs; 1,322 of 1,337 on 2026-10-10);
- the active site count would move more than 5% either way from the live one. A non-matching feed adds unmatched sites from both sides, so the count rises. One-change override: `SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT` (see the [README](../README.md#45-accepting-a-large-active-site-change-siteplain_data_accept_active_count)).

Because `nplstatus` decides which sites are active, a vocabulary change such as `Final` for `F` would otherwise empty the site. A `humexposurestscode` with no label is logged at WARN (`Human exposure feed has humexposurestscode values with no label: {...}`), not rejected; such a site shows `Exposure status: <CODE>` in gray. The swap runs in the single refresh transaction (see the [README](../README.md#26-the-swap-one-transaction)).

## Active sites

`nplstatus` from this feed decides whether a site is active: `COALESCE(npl_human_exposure.npl_status, npl_site_boundaries.npl_status_code) = 'F'`, so the boundary file's status is only a fallback for sites with no feed row. This is defined once, in the `active_npl_sites` view (migration V5), and every reader uses it: the address lookup, the site and state page caches, search, the sitemap and the active-site counts. Example: the six sites EPA deleted on 2026-08-20 (FLD032544587, NYD981486954, ILD980823991, KYD045738291, FLD076027820, GAD990741092) are `F` in the July boundary file but `D` in the feed, so they are not shown.

Active sites with no boundary (`has_boundary = false`, 15 on 2026-10-10) get a `/site/{epa_id}` page with a "no published boundary" notice, appear in search, the sitemap and a separate group on state pages, and are named on results pages for the address's state. They never appear in distance results, and no coordinates are invented for them.

## Exposure status codes

Labels as shipped (`ExposureStatusMapper`):

| Code | Label | Badge |
|---|---|---|
| HENC | Human exposure is NOT currently under control | Red |
| HEUC | Human exposure is under control | Yellow |
| HEPR | Human exposure is under control, protective remedies in place | Yellow |
| HHPA | Long-term health protection has been achieved | Green |
| HEID | Exposure status: insufficient data | Gray |
| HEIC | Exposure status: insufficient data | Gray (EPA publishes no definition for HEIC) |
| blank or no feed row | Exposure status not reported | Gray |
| any other code | Exposure status: CODE | Gray |

On 2026-10-10: HEPR 563, HEUC 533, HHPA 434, HEID 190, HENC 180, blank 3, HEIC 2 (both non-Final). The colors reflect EPA's exposure-control assessment, not whether a site is actively dangerous.

## The as-of date

Site pages, results pages and results PDFs show "EPA exposure status as of <date>": the `Last-Modified` of the live feed as recorded in `data_source_state.last_modified`, or, when none was recorded, `max(loaded_at)` of `npl_human_exposure`. Either is shown as a calendar date in America/New_York, since EPA publishes on Eastern time: an update EPA makes between 00:00 and 04:59 UTC shows as the previous day. The home and About pages also show "loaded <date>" for this dataset: when the live rows were swapped in, which moves only when the feed changed.

## Tables

| Table | Purpose |
|---|---|
| `npl_human_exposure` | Live rows, one per feed site. No foreign key to `npl_site_boundaries` since V5, so sites without a boundary are stored |
| `npl_human_exposure_staging` | A new feed while it is checked; empty between refreshes |

## Refresh

Automatic: see [The daily exposure refresh](../README.md#44-the-daily-exposure-refresh) for what runs, what it logs and the query that checks it. If EPA is down or the feed is rejected, the live rows stay, the app keeps serving, `data_source_state.last_error` says why, and the next run tries again. To pick up a change sooner, restart the service.

## Quirks

- A renamed `humanexposurepathdesc` or `friendlyurl` would not trip a guard (both are optional). After a feed change that looks odd, compare the `humexposurestscode` mix with the verification query in the README.
