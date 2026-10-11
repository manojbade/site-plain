# Site Plain Data

Every dataset the app loads, how it gets into the database, how to tell what is live, and how to refresh, roll back and recover it. One page per source lives in [sources/](sources/). Running the app (environment variables, token key, security headers, rate limits, healthcheck, memory, backups) is in [../operations.md](../operations.md).

Last verified: October 11, 2026, against commit 48cb5b7, a restore of the 2026-10-10 prod database, the local jar, and a local file server standing in for GitHub releases and EPA. Every command and query below was run in that setup unless it says otherwise. Sections 3.4, 4.2 steps 0 to 3, 6 and 7 were re-checked the same day after they were rewritten. Railway and GitHub write commands (`railway variable set`, `gh release create`) were checked against the CLI help only.

Contents:

1. [Source inventory](#1-source-inventory)
2. [How loading works](#2-how-loading-works)
3. [What is live right now](#3-what-is-live-right-now)
4. [Procedures](#4-procedures)
5. [Rollback and recovery](#5-rollback-and-recovery)
6. [Verification](#6-verification)
7. [Release retention](#7-release-retention)
8. [Timing](#8-timing)
9. [Variables that control loading](#9-variables-that-control-loading)
10. [Fragile points](#10-fragile-points)

---

## 1. Source inventory

| # | Dataset | Upstream | Format | Refresh | Loader | Table |
|---|---|---|---|---|---|---|
| [01](sources/01-epa-npl-boundaries.md) | NPL Superfund site boundaries | EPA EDG `NPL_Boundaries.zip`, converted and published as a GitHub release asset | GeoJSON (converted from a File Geodatabase) | Manual: publish a release, point `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` at it | `NplBoundaryLoader` | `npl_site_boundaries` |
| [02](sources/02-epa-human-exposure.md) | Human exposure status and NPL status | EPA SEMS `Human_Exposure_Site_List.json` | JSON | Automatic: checked on every startup and daily at 08:30 UTC, swapped in only when the file changed | `HumanExposureLoader` | `npl_human_exposure` |
| [03](sources/03-census-geocoder.md) | Address to coordinates | US Census Bureau geocoder | REST API | Live per lookup, nothing loaded | `GeocodingService` | none |
| [04](sources/04-nominatim-geocoder.md) | Address to coordinates (fallback) | OpenStreetMap Nominatim | REST API | Live per lookup, nothing loaded | `GeocodingService` | none |

Derived from the two EPA tables, rebuilt on every startup and whenever a source changes: `site_seo_page_cache` (one row per active site) and `state_page_cache` (one row per state with an active site), plus the in-memory `/sitemap.xml`. The `active_npl_sites` view (migration V5) is the one definition of an active site: the exposure feed's `nplstatus`, else the boundary file's `NPL_STATUS_CODE`, equal to `F`.

Bookkeeping: `data_source_state` (migration V7) holds one row per dataset with the identity of the live data and the result of the last refresh attempt. `npl_site_boundaries_staging` and `npl_human_exposure_staging` hold a new source while it is checked, and are empty between refreshes.

---

## 2. How loading works

### 2.1 When a refresh runs

`DataBootstrapService.refresh` runs:

- **At startup**, as an `ApplicationRunner`, after Flyway has migrated the schema and the web server has started. The app does not report ready (see [2.9](#29-readiness)) until it returns.
- **Daily**, from `ScheduledDataRefresh`, at `SITEPLAIN_DATA_SCHEDULED_REFRESH_CRON` (Spring cron in UTC, default `0 30 8 * * *`, 08:30 UTC). It skips while the app is not yet ready, so it never overlaps the startup run.

Neither runs when `SITEPLAIN_DATA_BOOTSTRAP_ENABLED=false`: the app then serves what is in the database and only rebuilds the sitemap from the page caches. A cron of `-` turns off the daily run alone.

### 2.2 The advisory lock

The whole refresh holds a Postgres session advisory lock (`DataRefreshLock`, key `0x535044415441`, "SPDATA"). Railway starts a new deployment while the old one still serves on the same database, so two instances can try to refresh at once; the one that cannot take the lock logs `Data refresh skipped: another instance holds the refresh lock ...; serving the data already live`, rebuilds its sitemap (at startup) and carries on serving. If the process dies, Postgres ends the session and frees the lock. Consequence: a deploy that lands while the old instance runs its 08:30 UTC refresh skips its own startup refresh. A changed boundary URL is then retried by the new instance's next daily run, or by a restart.

```sql
-- Is a refresh running anywhere right now? (one row while the lock is held)
SELECT pid, granted FROM pg_locks WHERE locktype = 'advisory' AND objid = x'44415441'::int AND classid = x'5350'::int;
```

### 2.3 What gets staged

**Boundaries** (`DataRefreshPolicyService`). One of three decisions, logged as `NPL boundaries: <ACTION> (<reason>)`:

| Decision | When | Effect |
|---|---|---|
| `RELOAD` | `npl_site_boundaries` is empty (`no boundaries are live (first boot)`); or at startup with `SITEPLAIN_DATA_REFRESH_ON_STARTUP=true`; or `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` differs from `data_source_state.source_url` for `npl_boundaries` (`SITEPLAIN_DATA_NPL_BOUNDARIES_URL changed from <old>`) | Download and stage the file |
| `ADOPT` | Boundaries are live but `data_source_state` has no `npl_boundaries` row (the first boot after migration V7) | Record the configured URL as their source, no download (see [2.7](#27-adoption-on-the-first-v7-boot)) |
| `KEEP` | Otherwise (`SITEPLAIN_DATA_NPL_BOUNDARIES_URL matches the live source`), or the URL is not set | Nothing |

There is no age trigger. `SITEPLAIN_DATA_MAX_AGE_DAYS` is ignored; when it is set the app logs `SITEPLAIN_DATA_MAX_AGE_DAYS=<n> is ignored ...` at WARN. The daily run never applies `SITEPLAIN_DATA_REFRESH_ON_STARTUP`, so it reloads boundaries only when the URL still differs from the live source, which means a reload that failed earlier is retried once a day.

**Exposure.** Downloaded on every run. It is staged unless all of these hold: `data_source_state` has a `human_exposure` row whose SHA-256 and URL equal the download's, and the live table still matches that row (same row count, no row newer than its `loaded_at`). Otherwise it is staged: a changed file, a changed URL, or a table rewritten by something else (an older app image, manual SQL; logged at WARN as `... no longer match ... Staging the feed again`).

### 2.4 Downloads

`SourceDownloader` fetches the URL (http, https, a `file:` URL or a plain path) to a temp file, following redirects (a GitHub release asset answers 302 to a CDN), and computes the SHA-256 while streaming. It fails the source, before anything is parsed, on: no response or no complete body within the timeout (the whole download counts), a body over the size cap, a non-2xx status, a `Content-Type` of `text/html`, an empty or blank body, or a body whose first non-blank character is not `{`. Limits: boundaries 300 s and 240 MB, exposure 60 s and 6 MB (variables in [section 9](#9-variables-that-control-loading)). Each download logs `Downloaded <label> from <url>: <bytes> bytes in <ms> ms, sha256 <hex>, Last-Modified <instant or none>`.

### 2.5 Validation gates

Each loader streams its file one record at a time into its staging table (the 58 MB boundary file reloads in a 48 MB heap), then the checks below run. Any failure rejects that source: it logs at ERROR, records `FAILED` with every failed check in `last_error` (counts plus up to five example IDs), and leaves live data alone.

Boundary file (`BoundaryFileStats`):

| Check | Threshold |
|---|---|
| Top-level `type` | `FeatureCollection` |
| `crs`, when present | CRS84 or `EPSG:4326` |
| `features` | present and not empty |
| `EPA_ID`, `SITE_NAME`, `STATE_CODE`, `NPL_STATUS_CODE` missing or blank | at most 0.5% of features each |
| Duplicate `EPA_ID` | none |
| Geometry | every feature a Polygon or MultiPolygon with numeric, correctly nested positions |
| Coordinates | longitude -180..180, latitude -90..90 |
| Final (`NPL_STATUS_CODE` `F`) count | 1,200..1,450 (1,328 in the July 2026 release) |
| Final feature without `STATE_CODE` | none |
| Rows staged | at least 90% of the live row count |

Exposure feed (`ExposureFeedStats`, then `DataBootstrapService`):

| Check | Threshold |
|---|---|
| `success` | not `false` |
| `data` array | present and not empty |
| `meta.total_data`, when present | equals the number of rows |
| `epaid`, `nplstatus`, `sitename`, usable state (`state_code`, else the EPA ID prefix) missing | at most 0.5% of rows each |
| `humexposurestscode` missing | at most 2% of rows (3 of 1,905 on 2026-10-10) |
| Duplicate `epaid` | none |
| Rows with an `epaid` | more than 1,500 (1,905 on 2026-10-10) |
| `nplstatus` values | only `F, D, P, N, R, S` |
| Final (`F`) rows | 1,200..1,450 (1,337 on 2026-10-10) |

Across both sources, computed on the active site set the swap would publish (staged data for each source being replaced, live data for the other):

| Check | Threshold | Override |
|---|---|---|
| Projected active sites with both a boundary and a feed row | at least 90% (1,322 of 1,337 on real data). Skipped when only boundaries change and no feed rows are live yet | none |
| Projected active site count against the live count | within 5% either way (skipped when no site is active yet, as on a first boot) | `SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT`, [4.5](#45-accepting-a-large-active-site-change-siteplain_data_accept_active_count) |
| Active sites after the swap | more than 0 (checked inside the transaction; rolls it back) | none |

A `humexposurestscode` with no label is logged at WARN (`... values with no label: {...}`), not rejected.

### 2.6 The swap: one transaction

When at least one source passed (and at every startup, even with nothing staged), one transaction does, in order: replace `npl_site_boundaries` from staging (if reloading), record its `data_source_state` row, replace `npl_human_exposure` from staging (if changed), record its row, rebuild `site_seo_page_cache`, rebuild `state_page_cache`. Each replace is `DELETE` plus `INSERT ... SELECT`, so readers keep seeing the old rows until commit, never an empty or half-built table. After commit: `ANALYZE` the replaced tables, empty both staging tables, rebuild the sitemap, and log `Data refresh done in a <n> ms swap. Boundaries <unchanged | reloaded (N rows)>, exposure <unchanged | replaced (N rows)>. SEO pages: N, State pages: N`. A daily run with nothing to swap logs `Data refresh done: no source changed` instead and touches no table but `data_source_state`.

Startup rebuilds the caches every time because a new app version can change how they are derived (labels, the active-site view).

### 2.7 Adoption on the first V7 boot

A database that had boundaries before migration V7 has no record of where they came from. On the first boot with V7, the app records the configured `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` as their source without downloading anything (`last_status = ADOPTED`, `sha256` and `bytes` empty until the next real load, `loaded_at` = when the live rows were loaded) and logs `Adopted <url> as the source of the <n> live NPL boundaries (loaded <time>) without reloading them`.

Adoption trusts the configured URL; it cannot tell which file the live rows came from. To find out which release they really are, use [3.4](#34-which-release-the-live-boundaries-are). **Do not change `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` in the deploy that first applies V7.** If you do, the new URL is recorded as the source of the old rows and never loaded. Change it in a later deploy, or set `SITEPLAIN_DATA_REFRESH_ON_STARTUP=true` for that one boot.

### 2.8 Independent sources and failures

- **Nothing live changes until the one transaction commits.** A failed download, a rejected file or a failed swap leaves the previous boundaries, exposure rows and page caches live.
- **With live data, a failed source never stops the app.** It is logged at ERROR (`NPL boundary file refresh failed; keeping the live data: <reason>` or `Human exposure feed refresh failed; keeping the live data: <reason>`), recorded as `FAILED` in `data_source_state` (the identity columns keep describing the live data), and the app becomes ready on the existing data.
- **The sources are independent.** One failing does not stop the other: a valid new feed is swapped in even when the boundary file was rejected, and the reverse.
- **Only an empty database stops startup.** On a first boot (no boundaries), any failure throws from the runner (`NPL boundary file failed and no data is live yet: ...`, then `Application run failed`) and the process exits. See [5.4](#54-failed-first-boot).
- The daily run follows the same rules. An unexpected error (the database unreachable) logs `Scheduled data refresh failed; keeping the live data`, and the next run tries again.

### 2.9 Readiness

- `/actuator/health/readiness` (readiness state plus a database check) answers 503 `OUT_OF_SERVICE` until the startup refresh has returned, then 200 `UP`. That is the Railway healthcheck path ([operations](../operations.md#healthcheck)): the old deployment keeps serving until the new one is ready, and a deployment that never turns ready (failed first boot, a bad token key, a refresh longer than the healthcheck timeout) is never switched in.
- `/actuator/health/liveness` is 200 once the context has started. `/actuator/health` is 200 during the startup refresh too and lists both groups.
- The two probe paths answer on any host and over plain HTTP. Every other path, `/actuator/health` included, redirects to `https://site-plain.com` in prod.

Railway containers are ephemeral. The database is the only state, and today the Postgres service has no volume (see the owner actions in [operations](../operations.md#owner-actions)), so a Postgres restart loses everything. Data in `npl_*` tables can be reloaded from the sources ([5.5](#55-database-lost)); `lookup_audit` and `feedback` cannot.

---

## 3. What is live right now

### 3.1 data_source_state

```sql
SELECT dataset, source_url, sha256, bytes, last_modified, row_count, final_count,
       loaded_at, last_checked_at, last_status, last_error
FROM data_source_state ORDER BY dataset;
```

| Column | Meaning |
|---|---|
| `source_url`, `sha256`, `bytes`, `last_modified` | The file the live rows came from: its URL, SHA-256, size and HTTP `Last-Modified`. Changed only by a successful swap (or adoption) |
| `row_count`, `final_count` | Rows loaded and how many of them were Final |
| `loaded_at` | When those rows went live (database clock) |
| `last_checked_at`, `last_status`, `last_error` | The last attempt: `LOADED` (swapped in), `UNCHANGED` (exposure download matched, nothing swapped), `ADOPTED` (see [2.7](#27-adoption-on-the-first-v7-boot)), `FAILED` (with the reason) |

Reading it:

- The live boundary release is `source_url` of `npl_boundaries`. Its `sha256` should equal the release asset's digest: `gh release view <tag> --repo manojbade/site-plain --json assets --jq '.assets[] | select(.name=="npl_site_boundaries.geojson") | .digest'` prints `sha256:<hex>`.
- **`ADOPTED` with an empty `sha256` and `bytes`** (the first boot after V7, [2.7](#27-adoption-on-the-first-v7-boot)) means `source_url` is only the URL that was configured at that boot. Identify the rows with [3.4](#34-which-release-the-live-boundaries-are).
- **`FAILED` describes the last attempt, not the live data.** A boundary row with `last_status = FAILED` still has the identity of the file that is live. It stays `FAILED` after you fix the cause by restoring the old URL (a `KEEP` updates nothing); it changes on the next real load. Compare `source_url` with the configured URL: if they are equal, the live data is that URL's file and the `last_error` is history.
- The boundary row is not touched by a `KEEP`, so its `last_checked_at` is the last reload, adoption or failed attempt. The exposure row's `last_checked_at` moves on every startup and daily run that downloaded the feed.

### 3.2 Log lines

| Line | Meaning |
|---|---|
| `NPL boundaries: KEEP (SITEPLAIN_DATA_NPL_BOUNDARIES_URL matches the live source)` | Routine boot |
| `NPL boundaries: RELOAD (SITEPLAIN_DATA_NPL_BOUNDARIES_URL changed from <old>)` | New release being loaded |
| `NPL boundaries: RELOAD (SITEPLAIN_DATA_REFRESH_ON_STARTUP=true)` | Forced re-download |
| `NPL boundaries: ADOPT (...)`, then WARN `Adopted <url> ...` | First V7 boot |
| `NPL boundaries loaded into staging: 1908 rows, 1328 Final` | File passed its checks |
| `Human exposure feed unchanged (sha256 <hex>); keeping the live rows` | Feed unchanged |
| `Data refresh done in a <n> ms swap. Boundaries ..., exposure .... SEO pages: 1337, State pages: 53` | Swap committed |
| `Data refresh done: no source changed` | Daily run, nothing to do |
| `Scheduled data refresh starting` | Daily run began |
| ERROR `... refresh failed; keeping the live data: <reason>` | Source failed or was rejected; live data unchanged |
| WARN `Data refresh skipped: another instance holds the refresh lock ...` | Another instance was refreshing |

Railway keeps deploy logs per deployment; to read the refresh lines of the current one: `railway logs --service site-plain --deployment --lines 300 --filter "Data refresh"` (flags from railway CLI 4.58 help, not run against prod here). The database row in 3.1 is the durable record; logs are not.

### 3.3 Dates on the pages

| Where | Text | Source |
|---|---|---|
| Home page, under the search | `EPA site boundaries loaded <date>` | Date of `max(loaded_at)` in `npl_site_boundaries` (app clock, UTC on Railway). Moves only on a boundary reload |
| Home "Trust and limits" and About "Data sources" | `loaded <date>` after each EPA dataset | Same boundary date; for exposure, date of `max(loaded_at)` in `npl_human_exposure`, which moves only when a changed feed was swapped in |
| Site page, results page, results PDF | `EPA exposure status as of <date>` | EPA's `Last-Modified` for the live feed (`data_source_state.last_modified`) as an America/New_York date; if none was recorded, `max(loaded_at)` of `npl_human_exposure`, also as an Eastern date |

So the boundary date tells you when the live release was loaded, not which release it is; use `data_source_state` for that, or 3.4.

### 3.4 Which release the live boundaries are

After a real load, `data_source_state` answers this: `source_url` plus a `sha256` equal to that release's digest ([section 7](#7-release-retention)). After adoption it does not: `sha256` is empty and `source_url` is whatever was configured. The counts of [section 6](#6-verification) do not help either: every release so far has 1,908 rows, 1,328 Final and the same status mix. Use one of these.

**A. Geometry fingerprint** (read-only, no deploy; on prod it needs a SQL session, [6.2](#62-sql-checks)):

```sql
-- The two sites whose geometry differs between the releases.
SELECT epa_id, round((st_area(geom::geography) / 1e6)::numeric, 3) AS km2
FROM npl_site_boundaries WHERE epa_id IN ('ORSFN1002155', 'WA0002329803') ORDER BY epa_id;
-- Every row's geometry in one value (about 0.2 s).
SELECT count(*) AS rows,
       left(md5(string_agg(epa_id || ':' || md5(ST_AsBinary(geom)), ',' ORDER BY epa_id)), 12) AS geometry_fingerprint
FROM npl_site_boundaries;
```

Look the result up in the release table of [section 7](#7-release-retention). The fingerprint depends only on the file, not on the app version that loaded it: the prod rows loaded on 2026-07-13 by the pre-October image and a load of the same file by the current image both give `3d43898d4fc4`. A release built with the current converter, which unions tied rows, shows about 18.190 and 5.240 km2 for the two sites ([source 01](sources/01-epa-npl-boundaries.md)); none is published yet. A fingerprint that matches no listed release means the rows came from no published file (hand edits, a local load): reload with B.

**B. Reload once and let the app record the SHA-256** (needs only the deploy logs). Set `SITEPLAIN_DATA_REFRESH_ON_STARTUP=true` for one boot ([4.3](#43-force-a-re-download-of-the-same-url)). That boot downloads the file at `source_url` again, logs `Downloaded NPL boundary file from <url>: <bytes> bytes in <ms> ms, sha256 <hex>, ...` and records it with `LOADED`, so from then on the live rows are that file by construction. It costs one deploy and about 5 s of loading. If the file differs a lot from the adopted rows the 5% gate rejects it and the adopted rows stay: that difference is itself the answer, and A tells you what they are.

Drill (October 11, 2026, restore of the 2026-10-10 prod dump): before V7 the two areas were 0.013 and 0.014 km2 and the fingerprint `3d43898d4fc4`, which is `data-2026-07-12`. After the first V7 boot (`ADOPTED`, empty `sha256`) A gave the same values; after one boot with B, the row showed `LOADED` with `3914ed30...7e48` and the fingerprint was unchanged.

---

## 4. Procedures

Railway commands assume the CLI is linked to the project (`railway link`) and use the app service name `site-plain`. Setting a variable without `--skip-deploys` starts a new deployment right away; batch changes with `--skip-deploys` and deploy once.

### 4.1 Converter setup (any machine)

The converter needs Python 3.9 to 3.12 (the pinned numpy and pyproj publish wheels for those versions on macOS arm64, Linux x86_64 and Windows; 3.13 and newer fail to install). macOS ships `/usr/bin/python3` 3.9 with the Xcode Command Line Tools; elsewhere install 3.12.

```bash
cd ~/workspace/site-plain
/usr/bin/python3 -m venv ~/.venvs/site-plain-converter          # or python3.12 -m venv ...
~/.venvs/site-plain-converter/bin/python -m pip install --upgrade pip
~/.venvs/site-plain-converter/bin/python -m pip install --only-binary=:all: -r scripts/requirements.txt
~/.venvs/site-plain-converter/bin/python scripts/convert_npl_to_geojson.py --self-test   # ends "Self-test passed."
```

`--only-binary=:all:` makes pip fail fast instead of trying to compile GDAL or PROJ. The install takes about 6 s. In a fresh venv built this way, converting the July 2026 GDB produced a file byte-identical to the one built on the original machine (SHA-256 `f979af8e...7c13`).

Flags:

| Flag | Default | Meaning |
|---|---|---|
| `--gdb PATH` | `/Users/manojbade/workspace/Info/superfund/superfund_audit_data/NPL_Boundaries/NPL_Boundaries.gdb` | The extracted `NPL_Boundaries.gdb` directory. Always pass it on another machine |
| `--output PATH` | `scripts/npl_site_boundaries.geojson` (gitignored) | Where to write the GeoJSON. Always pass it, pointing into the dated `$GDB_DIR` of [4.2](#42-publish-and-load-a-new-boundary-release): the default path overwrites without asking, and a checkout may keep a copy of a live release there. Keep the file name `npl_site_boundaries.geojson`: it becomes the release asset name and the file's own `"name"` member, so another name changes the bytes and the SHA-256 |
| `--min-final`, `--max-final` | 1200, 1450 | Accepted Final site count |
| `--max-null-rate` | 0.005 | Largest share of raw rows with a blank `EPA_ID`, `NPL_STATUS_CODE` or `STATE_CODE` |
| `--union-review-km2` | 2.0 | Union review gate size (see [source 01](sources/01-epa-npl-boundaries.md#union-review-gate)) |
| `--self-test` | | Synthetic checks, no GDB needed |

On any failed check the script prints `ERROR: input check failed, nothing written: <reason>` and exits 2. Do not loosen a flag to push a broken EPA release through: the 2026-10-10 EDG zip fails with `NPL_STATUS_CODE null/blank rate 5.35% exceeds 0.50% (113 rows); STATE_CODE null/blank rate 11.35% exceeds 0.50% (240 rows)`, and it must not be published.

### 4.2 Publish and load a new boundary release

Do this when EPA publishes boundaries you want (roughly yearly; the set of sites changes rarely) or when the converter changes. Before you start: `gh auth status` is logged in, the git tree is clean on `main`, and you know the live tag (`railway variable list --service site-plain --kv | grep '^SITEPLAIN_DATA_NPL_BOUNDARIES_URL='`; the tag is the path segment after `/download/`).

Every file in this procedure lives outside the repository: the new GeoJSON in a dated `$GDB_DIR`, and the copies of published releases in the release archive of [section 7](#7-release-retention). Nothing is written to `scripts/`.

0. **Check the copy of the live release** in the archive, so that step 3 has something to compare with and [5.3](#53-dead-or-broken-url) has something to re-publish:
   ```bash
   ARCHIVE=~/workspace/Info/superfund/site-plain-releases
   LIVE_TAG=data-2026-07-12                       # the live tag
   (cd "$ARCHIVE" && shasum -a 256 -c SHA256SUMS)  # every line ends "OK"
   grep "$LIVE_TAG/" "$ARCHIVE/SHA256SUMS"
   gh release view "$LIVE_TAG" --repo manojbade/site-plain --json assets \
     --jq '.assets[] | select(.name=="npl_site_boundaries.geojson") | .digest'
   ```
   The `SHA256SUMS` line for `$LIVE_TAG` and GitHub's `sha256:<hex>` must be the same value, and it must match the table in section 7. If the archive has no copy of the live tag, make one before going on and check it the same way:
   ```bash
   mkdir -p "$ARCHIVE/$LIVE_TAG"
   curl -fsSL -o "$ARCHIVE/$LIVE_TAG/npl_site_boundaries.geojson" \
     "https://github.com/manojbade/site-plain/releases/download/$LIVE_TAG/npl_site_boundaries.geojson"
   (cd "$ARCHIVE" && shasum -a 256 "$LIVE_TAG/npl_site_boundaries.geojson" >> SHA256SUMS)
   ```
1. **Download the GDB** into a fresh directory and note EDG's `Last-Modified` (it goes in the release notes):
   ```bash
   GDB_DIR=~/npl-gdb/$(date +%F)
   mkdir -p "$GDB_DIR" && cd "$GDB_DIR"
   curl -fsSI https://edg.epa.gov/data/PUBLIC/OLEM/OLEM-OSRTI/NPL_Boundaries.zip | grep -i last-modified
   curl -fSL -o NPL_Boundaries.zip https://edg.epa.gov/data/PUBLIC/OLEM/OLEM-OSRTI/NPL_Boundaries.zip   # about 21 MB, 6 s
   unzip -oq NPL_Boundaries.zip     # creates NPL_Boundaries.gdb/
   ```
   On 2026-10-11 the zip at that URL was still the broken 2026-10-10 file (`last-modified: Sat, 10 Oct 2026 08:46:47 GMT`); step 2 rejects it. The July 2026 GDB is kept at `/Users/manojbade/workspace/Info/superfund/superfund_audit_data/NPL_Boundaries/NPL_Boundaries.gdb`.
2. **Convert** into `$GDB_DIR` (about 6 s):
   ```bash
   cd ~/workspace/site-plain
   ~/.venvs/site-plain-converter/bin/python scripts/convert_npl_to_geojson.py \
     --gdb "$GDB_DIR/NPL_Boundaries.gdb" --output "$GDB_DIR/npl_site_boundaries.geojson"
   shasum -a 256 "$GDB_DIR/npl_site_boundaries.geojson"
   ```
   Expect about 2,114 raw features, 3 dropped source-control rows, about 1,908 unique sites, 19 tied sites unioned, 6 reviewed oversized unions and `Final NPL sites (status='F'): 1328` (July 2026 figures). `organizePolygons() received a polygon with more than 100 parts` warnings are harmless. If the union review gate fails, check the named site against EPA's site description before adding it to `REVIEWED_UNIONS` (a code change: commit it).
3. **Compare with the live release** (about 3 s, no download: the old side is the archive copy checked in step 0):
   ```bash
   python3 - "$ARCHIVE/$LIVE_TAG/npl_site_boundaries.geojson" "$GDB_DIR/npl_site_boundaries.geojson" <<'PY'
   import hashlib, json, sys
   def load(path):
       out = {}
       for f in json.load(open(path))["features"]:
           p = f["properties"]
           geom = hashlib.sha256(json.dumps(f["geometry"], sort_keys=True).encode()).hexdigest()
           out[p["EPA_ID"]] = (p.get("SITE_NAME"), p.get("STATE_CODE"), p.get("NPL_STATUS_CODE"), p.get("URL_ALIAS_TXT"), geom)
       return out
   old, new = load(sys.argv[1]), load(sys.argv[2])
   added, removed = sorted(new.keys() - old.keys()), sorted(old.keys() - new.keys())
   both = old.keys() & new.keys()
   attrs = sorted(i for i in both if old[i][:4] != new[i][:4])
   geoms = sorted(i for i in both if old[i][4] != new[i][4])
   final = lambda d: sum(1 for v in d.values() if v[2] == "F")
   print(f"sites {len(old)} -> {len(new)}, Final {final(old)} -> {final(new)}")
   print(f"added {len(added)}: {added[:20]}")
   print(f"removed {len(removed)}: {removed[:20]}")
   print(f"attributes changed {len(attrs)}: {attrs[:20]}")
   print(f"geometry changed {len(geoms)}: {geoms[:20]}")
   PY
   ```
   July 2026 release against the re-converted July GDB: 0 added, 0 removed, 0 attribute changes, 43 geometry changes (19 unions plus 24 repaired geometries). A Final count that moves more than 5%, or many added and removed IDs, will trip the load gates; find out why before publishing.
4. **Publish a release and archive it.** Name it `data-YYYY-MM-DD` with the date you publish (add `-2` for a second one that day). Never reuse a tag and never replace the asset of an existing release (see [section 7](#7-release-retention)). The asset takes the file's base name, so publish straight from `$GDB_DIR`:
   ```bash
   NEW_TAG=data-YYYY-MM-DD
   gh release create "$NEW_TAG" "$GDB_DIR/npl_site_boundaries.geojson" \
     --repo manojbade/site-plain --title "NPL boundary data YYYY-MM-DD" \
     --notes "EPA NPL Superfund Site Boundaries, converted from NPL_Boundaries.gdb (SITE_BOUNDARIES_SF, EDG Last-Modified <date>) with scripts/convert_npl_to_geojson.py at commit <git rev-parse --short HEAD>.
   - <unique sites> sites, <Final> Final. SHA-256 <hex>.
   - Change from $LIVE_TAG: <output of step 3>.
   Source: https://edg.epa.gov/data/PUBLIC/OLEM/OLEM-OSRTI/NPL_Boundaries.zip"
   curl -fsSL "https://github.com/manojbade/site-plain/releases/download/$NEW_TAG/npl_site_boundaries.geojson" | shasum -a 256   # same SHA-256 as step 2
   mkdir -p "$ARCHIVE/$NEW_TAG" && cp -p "$GDB_DIR/npl_site_boundaries.geojson" "$ARCHIVE/$NEW_TAG/"
   (cd "$ARCHIVE" && shasum -a 256 "$NEW_TAG/npl_site_boundaries.geojson" >> SHA256SUMS && shasum -a 256 -c SHA256SUMS)
   ```
   The asset URL is `https://github.com/manojbade/site-plain/releases/download/data-YYYY-MM-DD/npl_site_boundaries.geojson`.
5. **Point the app at it.** Nothing else is needed: the changed URL alone triggers one reload. Setting the variable starts a deployment.
   ```bash
   railway variable set SITEPLAIN_DATA_NPL_BOUNDARIES_URL=https://github.com/manojbade/site-plain/releases/download/data-YYYY-MM-DD/npl_site_boundaries.geojson --service site-plain
   ```
   Not during the first deploy of V7 ([2.7](#27-adoption-on-the-first-v7-boot)), and not around 08:30 UTC ([2.2](#22-the-advisory-lock)).
6. **Watch the deploy logs** for, in order: `NPL boundaries: RELOAD (SITEPLAIN_DATA_NPL_BOUNDARIES_URL changed from <old>)`, `Downloaded NPL boundary file from <url>: <bytes> bytes ..., sha256 <hex>`, `NPL boundaries loaded into staging: <n> rows, <n> Final`, `Data refresh done in a <n> ms swap. Boundaries reloaded (<n> rows) ...`. A rejected or unreachable file logs `NPL boundary file refresh failed; keeping the live data: <reason>` and the old boundaries stay live: fix the file or go back to the old URL ([5.3](#53-dead-or-broken-url)).
7. **Verify from the logs** ([6.1](#61-prod-check-from-the-deploy-logs)): the `sha256` in the `Downloaded` line equals step 2 and the GitHub digest, the swap line says `Boundaries reloaded (<unique sites from step 2> rows)`, and the live-site checks pass. That is the complete prod check. The SQL of [6.2](#62-sql-checks) (`data_source_state` showing the new URL and SHA-256 with `LOADED`) is optional on prod because it needs the plaintext TCP proxy.
8. **Update [section 7](#7-release-retention)**: add the new tag to the release table (size and SHA-256 from step 2; the fingerprint values of [3.4](#34-which-release-the-live-boundaries-are) if you have a SQL session on a database that loaded it), mark it live, mark `$LIVE_TAG` as previous. Commit the doc change.

### 4.3 Force a re-download of the same URL

Normally never needed: a release asset never changes. Use it to repair rows changed by hand, or to restage boundaries in the same boot as a feed whose IDs changed.

```bash
railway variable set SITEPLAIN_DATA_REFRESH_ON_STARTUP=true --service site-plain          # deploys; that boot logs RELOAD (SITEPLAIN_DATA_REFRESH_ON_STARTUP=true)
# after the logs show "Data refresh done in a ... Boundaries reloaded":
railway variable set SITEPLAIN_DATA_REFRESH_ON_STARTUP=false --service site-plain --skip-deploys
```

Set it back to `false` (or delete it): left `true`, every restart and deploy downloads and reloads boundaries again. It forces only the boundary reload; the exposure feed is still staged only when it changed.

### 4.4 The daily exposure refresh

Every day at 08:30 UTC each running instance downloads the feed. If it is byte-for-byte the live feed, nothing is swapped: `data_source_state` gets `last_status = UNCHANGED` and a new `last_checked_at`, and the log shows `Scheduled data refresh starting`, `Downloaded Human exposure feed ...`, `Human exposure feed unchanged ...`, `Data refresh done: no source changed` (about 60 ms). If it changed, it goes through the gates and the one-transaction swap with both page caches (about 0.6 s), and the as-of date on the pages moves to EPA's new `Last-Modified`. A failed or rejected feed keeps the live rows and records `FAILED`; the next day tries again. Boundaries are left alone unless a reload is still pending ([2.3](#23-what-gets-staged)).

Check it:

```sql
-- Expect UNCHANGED or LOADED, and fresh = true.
SELECT last_status, last_error, last_checked_at, now() - last_checked_at < interval '26 hours' AS fresh,
       loaded_at AS feed_swapped_in_at, last_modified AS epa_last_modified
FROM data_source_state WHERE dataset = 'human_exposure';
```

To pick up an EPA change sooner, restart the service (`railway restart --service site-plain`); startup runs the same check.

### 4.5 Accepting a large active-site change (SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT)

A refresh that would move the number of active sites more than 5% is rejected with:

```
Active site count would drop from 1337 to 1257, more than 5%. If this change is expected, set SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT=1257 for the next boot
```

If EPA really made that change (check the feed or the release diff first):

```bash
railway variable set SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT=1257 --service site-plain          # deploys
# logs: "Active site count would drop from 1337 to 1257, more than 5%; accepted because SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT=1257"
railway variable delete SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT --service site-plain           # may start a deployment; harmless
```

It accepts only a refresh whose projected count is exactly that number; every other count is still gated, so a value left set stops mattering once that count is live (drill: with 1257 still set, the next change back to 1337 was rejected). It applies to the daily run too, and it never overrides the 90% ID-match check.

---

## 5. Rollback and recovery

### 5.1 Data rollback to the previous release

Point the URL back at the previous release; the changed URL triggers one reload of it:

```bash
railway variable set SITEPLAIN_DATA_NPL_BOUNDARIES_URL=https://github.com/manojbade/site-plain/releases/download/<previous tag>/npl_site_boundaries.geojson --service site-plain
```

Logs: `NPL boundaries: RELOAD (SITEPLAIN_DATA_NPL_BOUNDARIES_URL changed from <bad release>)`, then the usual staging and swap lines. Afterwards `data_source_state` shows the previous URL and its SHA-256 with `LOADED`. The gates apply to a rollback too: if the active count differs by more than 5%, use [4.5](#45-accepting-a-large-active-site-change-siteplain_data_accept_active_count). Total about one deploy plus 5 s of loading.

### 5.2 App rollback

In the Railway dashboard, open the service's deployment list and use the earlier deployment's menu (Rollback or Redeploy); or deploy an older commit with `railway up --service site-plain` from a clean checkout of it (`git worktree add ../site-plain-<sha> <sha>`). Migrations are never rolled back: the database stays at V7, and every image tolerates that (the older one logs `Schema "public" has a version (7) that is newer than the latest available migration (4) !` and starts).

**Back to an image from this release (commit 48cb5b7 or later):** nothing special. Same key, same links, same healthcheck.

**Back to the image before this release** (the one deployed 2026-07-13, commit 3dd462c), checked in a drill on a V7 database:

- **Healthcheck:** that image has no `/actuator/health/readiness`, and its canonical-host filter redirects every path (308 to `https://site-plain.com/...`) on the healthcheck's host, so the Railway healthcheck can never pass and the rollback would never go live. Clear the service's Healthcheck Path in Railway settings before redeploying it, and set it back to `/actuator/health/readiness` when you roll forward.
- **Results links:** it ignores `?r=`, so every link made by this release shows "Address could not be resolved". Old-format links (`?lat=...&lng=...`) work on both.
- **Data:** on every boot it reloads the exposure feed outside a transaction, keeps only the 1,886 rows that have a boundary and leaves `state_code` and `epa_url` empty; it decides active sites from the boundary file, so the 15 Final sites without a boundary disappear and the 6 sites EPA deleted in August 2026 show again. It never writes `data_source_state`.
- **Boundaries:** it still has the 90-day age trigger. If `SELECT max(loaded_at) FROM npl_site_boundaries` is more than 90 days old it reloads boundaries at boot, non-transactionally, with a loader that buffers the whole file (needs about 384 MB of heap, so it runs out of memory under `JAVA_OPTS=-Xmx256m`). Prevent that for the rollback with `railway variable set SITEPLAIN_DATA_MAX_AGE_DAYS=3650 --service site-plain --skip-deploys`; the current image ignores the variable (one WARN line), so it can stay until you roll forward.
- **Rolling forward again** repairs the data with no action: the current image sees that the exposure rows no longer match its record (`... the live rows (1886) no longer match the 1905 recorded ...: something else wrote npl_human_exposure. Staging the feed again`), restages the feed and rebuilds the caches. Then delete `SITEPLAIN_DATA_MAX_AGE_DAYS` again.

### 5.3 Dead or broken URL

Symptom: ERROR `NPL boundary file refresh failed; keeping the live data: NPL boundary file: HTTP 404 from <url> ...` (or a timeout, an HTML page, a rejected file). The app is up on the old boundaries, `data_source_state.npl_boundaries` shows `FAILED` with the reason, and the reload is retried at every restart and every daily run until it loads or the URL changes.

Recovery, either:

- Fix the source: publish a good release under a **new** tag and set the URL to it ([4.2](#42-publish-and-load-a-new-boundary-release), steps 4 to 7).
- Or go back to the URL that is live: set `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` to the `source_url` in `data_source_state` (the release that is actually loaded). The next boot logs `KEEP` and downloads nothing. `last_status` stays `FAILED` with the old error until the next real load; that is expected ([3.1](#31-data_source_state)).

If the live release itself was deleted from GitHub, nothing breaks until the next reload of that URL (a forced re-download, or an empty database). Re-publish the archived copy under a new tag and point the URL at it: check it first with `(cd ~/workspace/Info/superfund/site-plain-releases && shasum -a 256 -c SHA256SUMS)`, then run [4.2](#42-publish-and-load-a-new-boundary-release) steps 4 to 7 with `$GDB_DIR` replaced by `~/workspace/Info/superfund/site-plain-releases/<deleted tag>` (skip the archive copy in step 4; it is already there). The re-published file has the same SHA-256 as the deleted one.

The exposure URL fails the same way (`Human exposure feed refresh failed; keeping the live data: ...`) and needs no action for a short EPA outage: the live rows stay and the next run retries. If EPA moves the feed, set `SITEPLAIN_DATA_HUMAN_EXPOSURE_URL` to the new location.

### 5.4 Failed first boot

**Empty database** (new Postgres, or after a data loss). Any source failure aborts startup: `NPL boundary file failed and no data is live yet: <reason>`, then `Application run failed`, and the process exits. Migrations are applied and a `FAILED` row is recorded; nothing else is written. With the readiness healthcheck, Railway never switches to this deployment, but with an empty database there is nothing to serve anyway. Fix the cause (usually `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` unset or wrong, or EPA unreachable) and deploy again; the next boot logs `RELOAD (no boundaries are live (first boot))` and loads both sources (about 6 s).

**First deploy of this release on the existing prod database** (applies V5 to V7). The first boot adopts the boundaries and restages the exposure feed. It cannot lose data: the old deployment keeps serving until readiness, the old image runs fine on the migrated schema (5.2), and a failed source just keeps the old rows. If the new deployment never turns ready, read its deploy logs: `SITEPLAIN_RESULTS_TOKEN_KEY must decode to 32 bytes` or `... is not valid base64` (fix the key), `OutOfMemoryError` (fix `JAVA_OPTS`), or a refresh that outlasted the healthcheck timeout (raise it to 600 s). Then redeploy. If the deploy changed the boundary URL by mistake (so the old rows were adopted under the new URL), set `SITEPLAIN_DATA_REFRESH_ON_STARTUP=true` for one boot ([4.3](#43-force-a-re-download-of-the-same-url)).

**Emergency lever:** `SITEPLAIN_DATA_BOOTSTRAP_ENABLED=false` boots on whatever is in the database with no downloads and no daily refresh (the sitemap is still built, readiness turns UP at once). Use it to stop all refreshes, for example when EPA publishes a feed that passes the gates but is wrong, until EPA fixes it; a data rollback (5.1) needs it back at `true`. Set it back to `true` after.

### 5.5 Database lost

If Postgres loses its data (today a Postgres restart does, see the owner actions in [operations](../operations.md#owner-actions)), restore the latest backup ([operations](../operations.md#backups)) and restart the app. Without a backup, restart the app on the empty database: it applies all migrations and loads both sources from `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` and the EPA feed (5.4). Site data comes back; `lookup_audit` and `feedback` do not.

---

## 6. Verification

After any load, rollback or recovery. On prod, 6.1 is the check: it needs only the deploy logs and `curl`. The SQL of 6.2 is what to run locally and in drills; on prod it is optional, because the only way in is the Postgres TCP proxy, which is plaintext and stays disabled ([owner action 3](../operations.md#owner-actions)).

### 6.1 Prod check from the deploy logs

For the deployment that did the load (`railway logs` defaults to the latest successful deployment; pass its ID for an older one):

```bash
railway logs --service site-plain --deployment --lines 300 --filter "NPL"
railway logs --service site-plain --deployment --lines 300 --filter "Data refresh"
gh release view <tag> --repo manojbade/site-plain --json assets \
  --jq '.assets[] | select(.name=="npl_site_boundaries.geojson") | .digest'
```

Pass when:

- `Downloaded NPL boundary file from <the release URL>: <bytes> bytes in <ms> ms, sha256 <hex>, ...`: the `<hex>` equals GitHub's digest and the release table in [section 7](#7-release-retention) (`data-2026-07-12`: 57,800,609 bytes, `3914ed30...7e48`).
- `NPL boundaries loaded into staging: 1908 rows, 1328 Final` and `Data refresh done in a <n> ms swap. Boundaries reloaded (1908 rows), exposure .... SEO pages: 1337, State pages: 53` (figures for `data-2026-07-12` and the 2026-10-10 feed).
- No `NPL boundary file refresh failed` line after them, and the live-site checks below pass.

A deployment that only logs `NPL boundaries: KEEP (...)` downloaded nothing: its logs prove the configured URL equals the recorded source, not which file the rows hold. That proof is in the logs of the deployment that did the reload. Railway keeps logs per deployment, not forever; for adopted rows, or once those logs are gone, use [3.4](#34-which-release-the-live-boundaries-are) B to produce a fresh `Downloaded` line. The `railway logs` flags are from the railway CLI 4.58 help and were not run against prod; the log lines were checked on the local jar.

Live site (read-only GETs; expected output on the right):

```bash
curl -s https://site-plain.com/actuator/health/readiness                                  # {"status":"UP"}
curl -s https://site-plain.com/actuator/health                                            # {"status":"UP","groups":["liveness","readiness"]}
curl -s https://site-plain.com/site/GAD990855074 | grep -o 'EPA exposure status as of [A-Za-z]* [0-9]*, [0-9]*'
curl -s -o /dev/null -w '%{http_code}\n' https://site-plain.com/site/WA2170023418          # 200: a site with no boundary has a page
curl -s "https://site-plain.com/api/site-search/suggestions?q=firestone" | head -c 200      # JSON naming GAD990855074
curl -s https://site-plain.com/sitemap.xml | grep -o '<loc>' | wc -l                        # 1392 (active sites + states + fixed pages)
curl -s https://site-plain.com/ | grep -o 'loaded [A-Z][a-z]* [0-9]*, [0-9]*'             # the two load dates of 3.3
```

Then do one address lookup in a browser (a `curl` POST to `/lookup` without an `Origin` header is refused with 403 by design).

### 6.2 SQL checks

Values are from the drill database after loading the July 2026 release and the 2026-10-10 feed. Locally, run them with `psql` against the database the jar uses.

On prod, only when the logs cannot answer (for example [3.4](#34-which-release-the-live-boundaries-are) A), and as one short session:

1. Railway dashboard, `postgres-postgis` service, Settings, Networking: enable the TCP Proxy.
2. `railway connect postgres-postgis` opens psql through it. The server runs with `ssl=off`, so the session, password included, crosses the internet in plaintext: run only the read-only queries below, never `SELECT` from `feedback` or `lookup_audit`, and quit.
3. Back in the same settings panel, remove the TCP Proxy, and check that `railway connect postgres-postgis` no longer connects.

The dashboard steps were not run here; the setting names may differ in Railway's current UI.

```sql
-- 1. What is live and how the last attempt went (see 3.1).
SELECT dataset, source_url, left(sha256, 12) AS sha256, bytes, last_modified, row_count, final_count,
       loaded_at, last_checked_at, last_status, last_error
FROM data_source_state ORDER BY dataset;

-- 2. The live tables match what was recorded (live_rows = recorded_rows: 1908/1908 and 1905/1905).
SELECT 'npl_boundaries' AS dataset, (SELECT count(*) FROM npl_site_boundaries) AS live_rows,
       (SELECT row_count FROM data_source_state WHERE dataset = 'npl_boundaries') AS recorded_rows
UNION ALL
SELECT 'human_exposure', (SELECT count(*) FROM npl_human_exposure),
       (SELECT row_count FROM data_source_state WHERE dataset = 'human_exposure');

-- 3. Active sites as users see them: 1337 active, 15 without a boundary.
SELECT count(*) AS active_sites, count(*) FILTER (WHERE NOT has_boundary) AS without_boundary FROM active_npl_sites;
-- Page caches: 1337 site pages, 53 state pages holding 1337 sites. NJ 115, CA 97, PA 91 lead.
SELECT (SELECT count(*) FROM site_seo_page_cache) AS site_pages,
       (SELECT count(*) FROM state_page_cache) AS state_pages,
       (SELECT sum(site_count) FROM state_page_cache) AS state_page_sites;
SELECT state_code, site_count FROM state_page_cache ORDER BY site_count DESC, state_code LIMIT 3;

-- 4. Status mix. Boundaries: F 1328, D 459, N 70, P 36, R 10, A 3, S 2.
--    Feed nplstatus: F 1337, D 466, N 60, P 37, R 3, S 2.
--    Exposure codes: HEPR 563, HEUC 533, HHPA 434, HEID 190, HENC 180, blank 3, HEIC 2.
SELECT npl_status_code, count(*) FROM npl_site_boundaries GROUP BY 1 ORDER BY 2 DESC;
SELECT npl_status, count(*) FROM npl_human_exposure GROUP BY 1 ORDER BY 2 DESC;
SELECT humexposurestscode, count(*) FROM npl_human_exposure GROUP BY 1 ORDER BY 2 DESC;

-- 5. Staging is empty (0, 0); the boundary indexes exist (idx_npl_boundaries_geog, _geom, _state, _status, pkey).
SELECT (SELECT count(*) FROM npl_site_boundaries_staging) AS boundary_staging,
       (SELECT count(*) FROM npl_human_exposure_staging) AS exposure_staging;
SELECT indexname FROM pg_indexes WHERE tablename = 'npl_site_boundaries' ORDER BY indexname;

-- 6. The daily refresh checked the feed within 26 hours (fresh = t).
SELECT last_checked_at, now() - last_checked_at AS since_check,
       now() - last_checked_at < interval '26 hours' AS fresh
FROM data_source_state WHERE dataset = 'human_exposure';

-- 7. Spot checks: a Final site with a boundary, and one without (has_boundary f).
SELECT epa_id, site_name, state_code, npl_status, has_boundary FROM active_npl_sites
WHERE epa_id IN ('GAD990855074', 'WA2170023418') ORDER BY epa_id;
```

`GAD990855074` is Firestone Tire & Rubber Co. (Albany Plant), GA. Older notes used `GA8213820731`, which is not in EPA's data.

---

## 7. Release retention

Published releases (the asset is always `npl_site_boundaries.geojson`; SHA-256 and size are GitHub's `digest` and `size`, equal to the archive copies; the last two columns are the [3.4](#34-which-release-the-live-boundaries-are) A values):

| Tag | Published | Role | Bytes | SHA-256 | ORSFN1002155 / WA0002329803 km2 | Geometry fingerprint |
|---|---|---|---|---|---|---|
| `data-2026-07-12` | 2026-07-13 00:31 UTC | **live** (prod rows loaded 2026-07-13 00:36 UTC) | 57,800,609 | `3914ed309daba1b3a26b494b851b0b0626d28780e71cc85f33ce272a2ca67e48` | 0.013 / 0.014 | `3d43898d4fc4` |
| `data-v1` | 2026-03-23 20:58 UTC | previous (rollback target) | 57,821,970 | `a2afb167dc8ce03d1abb7fb9903b32235fd74c3adcdcb378d2f1227c74d18069` | 0.243 / 0.002 | `18242cae88ea` |

Both have 1,908 sites and 1,328 Final with the same status mix; only the geometry tells them apart.

**Local archive.** A copy of every release in the table is kept at `~/workspace/Info/superfund/site-plain-releases/<tag>/npl_site_boundaries.geojson` (outside any git repository), with their SHA-256 values in `~/workspace/Info/superfund/site-plain-releases/SHA256SUMS`. Check it with `(cd ~/workspace/Info/superfund/site-plain-releases && shasum -a 256 -c SHA256SUMS)`. It is on this laptop only: GitHub is the other copy, so copy the folder to a second place (an external drive, a cloud folder) after each release. [4.2](#42-publish-and-load-a-new-boundary-release) step 0 checks the archive and step 4 adds the new release to it. Do not keep release copies in `scripts/`: it is gitignored scratch, and the converter writes its default output there.

Rules:

- **Never delete** the release `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` points to, or the one before it (the rollback target of [5.1](#51-data-rollback-to-the-previous-release)). The app needs the current one again whenever the database is rebuilt or a re-download is forced.
- **Never edit a release or replace its asset.** A tag names one file forever: the app decides on the URL, so a replaced asset is never loaded by a running site, and `data_source_state.sha256` would no longer match GitHub's digest. Publish a new tag instead.
- Older releases may be deleted from GitHub once the current one shows `LOADED` with a SHA-256 equal to its GitHub digest (or its `Downloaded` log line does, [6.1](#61-prod-check-from-the-deploy-logs)). After the next release goes live, `data-v1` can go. Keep its archive copy and its row in this table, marked deleted.

---

## 8. Timing

Measured locally on an M4 MacBook Pro against a prod-sized database; Railway adds its build and deploy time.

| Step | Time |
|---|---|
| Converter venv install | about 6 s |
| Download the EDG zip (21 MB) and convert | a few seconds plus about 6 s |
| Compare with the live release (download 58 MB, compare) | about 5 s plus 3 s |
| `gh release create` (58 MB upload) | depends on upload speed, typically under a minute |
| Railway deploy after a variable change, until the new container starts | 1 to 2 minutes (July 2026 deploy timestamps; not measured here) |
| Startup to ready, nothing to reload | about 2 s (JVM start about 1.5 s, refresh 0.1 to 0.2 s) |
| Startup to ready, first V7 boot (migrations, adoption, feed restage) | about 3.5 s |
| Startup to ready with a boundary reload | 5 to 6.5 s: download (0.2 s from a local server, 2 to 5 s from GitHub), staging 3.3 to 3.6 s, swap 0.5 to 0.75 s |
| First boot on an empty database | about 6 s with local files; about 10 s from JVM start with the real GitHub and EPA downloads |
| Daily refresh, feed unchanged / changed | about 60 ms / about 0.6 s |
| Old deployment keeps serving until the new one is ready | always, with the readiness healthcheck |
| Worst case, boundary download hangs | readiness held for the 300 s download timeout, then the app serves the old data; within the 600 s healthcheck timeout |

---

## 9. Variables that control loading

All `SITEPLAIN_*` variables are in [operations](../operations.md#environment-variables); these are the data ones.

| Variable | Steady-state value | Effect |
|---|---|---|
| `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` | Current release asset URL | Where boundaries come from. Changing it triggers one reload. Required for a first boot |
| `SITEPLAIN_DATA_HUMAN_EXPOSURE_URL` | unset (default `https://www3.epa.gov/semsjson/Human_Exposure_Site_List.json`) | The exposure feed |
| `SITEPLAIN_DATA_REFRESH_ON_STARTUP` | `false` | `true` reloads boundaries on every boot ([4.3](#43-force-a-re-download-of-the-same-url)) |
| `SITEPLAIN_DATA_BOOTSTRAP_ENABLED` | `true` | `false` skips all downloads and the daily refresh ([5.4](#54-failed-first-boot)) |
| `SITEPLAIN_DATA_SCHEDULED_REFRESH_CRON` | unset (`0 30 8 * * *`, UTC) | Daily refresh time, Spring cron with seconds; `-` turns it off |
| `SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT` | unset | One-change override of the 5% gate ([4.5](#45-accepting-a-large-active-site-change-siteplain_data_accept_active_count)) |
| `SITEPLAIN_DATA_BOUNDARIES_DOWNLOAD_TIMEOUT` / `SITEPLAIN_DATA_BOUNDARIES_MAX_SIZE` | unset (`300s` / `240MB`) | Whole-download limit and size cap for the boundary file |
| `SITEPLAIN_DATA_HUMAN_EXPOSURE_DOWNLOAD_TIMEOUT` / `SITEPLAIN_DATA_HUMAN_EXPOSURE_MAX_SIZE` | unset (`60s` / `6MB`) | The same for the feed |
| `SITEPLAIN_DATA_MAX_AGE_DAYS` | unset | Ignored (WARN when set). Only useful during a rollback to the pre-release image ([5.2](#52-app-rollback)) |

---

## 10. Fragile points

| Risk | What happens | What to do |
|---|---|---|
| EPA publishes a broken GDB (the 2026-10-10 zip has 5.35% blank status and 11.35% blank state) | Converter exits 2 and writes nothing | Wait for a fixed release; keep the live one |
| EPA renames the GDB layer (`SITE_BOUNDARIES_SF`) | Converter fails reading | Update `LAYER` in the script; list layers with `python -c "import pyogrio; print(pyogrio.list_layers('<path>/NPL_Boundaries.gdb'))"` in the converter venv |
| EPA renames exposure fields | Feed rejected by the 0.5% and 2% missing-field gates; live rows stay | Update `HumanExposureLoader`. Renamed optional fields (`humanexposurepathdesc`, `friendlyurl`) do not trip a gate; check pages after a feed change looks odd |
| EPA changes `nplstatus` vocabulary or ID format | Rejected by the status-value, Final-band or ID-match gates | Update the loader; if both sources change IDs, the boundary release must change too |
| GitHub release deleted or renamed | Nothing until the next reload of that URL; then [5.3](#53-dead-or-broken-url) | Re-publish under a new tag |
| EDG catalog moves | The data.gov slug `npl-superfund-site-boundaries-epa` now 404s | Find the dataset from EPA's catalog entry (UUID FC07D75C-8596-434B-B1A6-0688C9CD45B5, linked on the About page) |
| Heap too small | Staging streams one feature at a time: the 58 MB release reloads at `-Xmx48m`, fails at `-Xmx40m`. Results pages with very large site geometries are the real heap driver (see [operations](../operations.md#java_opts)); an `OutOfMemoryError` during boot exits the app with live data unchanged | Keep `-Xmx` at 192m or more |
| PostGIS missing on a new Postgres | V1 runs `CREATE EXTENSION IF NOT EXISTS postgis`, which fails | Use a PostGIS image (prod: `postgis/postgis`, 16-3.4 locally) |
