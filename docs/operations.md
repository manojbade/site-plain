# Operating Site Plain

How the app runs in production and what each setting does. Data loading, refresh, rollback and recovery are in [data/README.md](data/README.md).

Last verified: October 11, 2026, against commit 48cb5b7 run locally (prod profile, a restore of the prod database). Railway settings and owner actions were not changed or read from Railway; the Railway commands are from the railway CLI 4.58 help.

Contents:

- [Production setup](#production-setup)
- [Environment variables](#environment-variables)
- [Results token key](#results-token-key)
- [Security headers and CSP](#security-headers-and-csp)
- [Rate limits and global caps](#rate-limits-and-global-caps)
- [Healthcheck](#healthcheck)
- [Deploying and what to watch](#deploying-and-what-to-watch)
- [JAVA_OPTS](#java_opts)
- [Backups](#backups)
- [Running locally and tests](#running-locally-and-tests)
- [Owner actions](#owner-actions)

---

## Production setup

- Railway project with two services: `site-plain` (this app) and `postgres-postgis` (PostgreSQL 16 with PostGIS). One replica.
- Build: Railpack builds the Maven project on Railway and starts the jar with `java $JAVA_OPTS -jar` (it also passes Railway's `PORT`).
- Container limit 8 GB / 8 vCPU. Without `JAVA_OPTS` the JVM takes a 2 GB max heap and G1 (see [JAVA_OPTS](#java_opts)).
- The app reaches Postgres on Railway's private network (`postgres-postgis.railway.internal`). Cloudflare sits in front of `site-plain.com`.
- State: only the database. The container filesystem is thrown away on every deploy, and the Postgres service has no volume yet ([owner actions](#owner-actions)).

---

## Environment variables

Spring binds `siteplain.*` properties from environment variables by relaxed binding: upper case, dots become underscores, and camel case is run together with no underscore. So `siteplain.limits.feedback.capacity` is `SITEPLAIN_LIMITS_FEEDBACK_CAPACITY` and `siteplain.geocoder.censusBaseUrl` is `SITEPLAIN_GEOCODER_CENSUSBASEURL`. A few properties have a named variable in `application.properties` instead (for example `SITEPLAIN_BASE_URL`); use the names in the tables. Durations take `500ms`, `3s`, `12m`, `7d`; sizes take `6MB`. Names marked "checked" were confirmed to take effect on the local jar; the others bind the same way.

### Required in production

| Variable | Effect |
|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | Prod profile: datasource from the `SPRING_DATASOURCE_*` variables, forwarded headers trusted, canonical-host redirect on, template cache on. The default profile is `dev` (needs `DEV_DATABASE_URL`, `DEV_DB_USERNAME`, `DEV_DB_PASSWORD`) |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Postgres connection (JDBC URL) |
| `SITEPLAIN_RESULTS_TOKEN_KEY` | Results link key, see [below](#results-token-key). Unset: a random key per start and a WARN; links break on every restart. A value that is not base64 of exactly 32 bytes stops startup |
| `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` | Boundary release asset URL. Required to load an empty database; changing it reloads boundaries ([data README](data/README.md#42-publish-and-load-a-new-boundary-release)) |
| `MAPBOX_PUBLIC_TOKEN` | Mapbox public token for the results map and home-page address suggestions. Unset: no map and no suggestions |

### Data loading

| Variable | Default | Effect |
|---|---|---|
| `SITEPLAIN_DATA_BOOTSTRAP_ENABLED` | `true` | `false` skips every download and the daily refresh; serves what is in the database |
| `SITEPLAIN_DATA_REFRESH_ON_STARTUP` | `false` | `true` reloads boundaries on every boot. One-boot use only |
| `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` | none | See above |
| `SITEPLAIN_DATA_HUMAN_EXPOSURE_URL` | `https://www3.epa.gov/semsjson/Human_Exposure_Site_List.json` | Exposure feed |
| `SITEPLAIN_DATA_BOUNDARIES_DOWNLOAD_TIMEOUT` | `300s` | Whole boundary download limit |
| `SITEPLAIN_DATA_BOUNDARIES_MAX_SIZE` | `240MB` | Boundary file size cap |
| `SITEPLAIN_DATA_HUMAN_EXPOSURE_DOWNLOAD_TIMEOUT` | `60s` | Whole feed download limit |
| `SITEPLAIN_DATA_HUMAN_EXPOSURE_MAX_SIZE` | `6MB` | Feed size cap |
| `SITEPLAIN_DATA_SCHEDULED_REFRESH_CRON` | `0 30 8 * * *` | Daily refresh, Spring cron with seconds, UTC. `-` turns it off |
| `SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT` | unset | Accepts one refresh whose active-site count is exactly this, past the 5% gate (checked) |
| `SITEPLAIN_DATA_MAX_AGE_DAYS` | unset | Ignored; logs a WARN when set. Delete it (keep it only during a rollback to the pre-October image) |

### Results links and security

| Variable | Default | Effect |
|---|---|---|
| `SITEPLAIN_RESULTS_TOKEN_KEY` | unset (random per start) | AES-256-GCM key for results links (checked) |
| `SITEPLAIN_SECURITY_CSP_MODE` | `enforce` | `report-only` sends `Content-Security-Policy-Report-Only` instead (checked) |
| `SITEPLAIN_BASE_URL` | `https://site-plain.com` | Canonical origin: redirect target, sitemap and canonical links |
| `SITEPLAIN_SITE_ENFORCECANONICALHOST` | `false`, `true` in the prod profile | Redirect (308) any other host or scheme to `SITEPLAIN_BASE_URL`, except the two probe paths (checked) |

### Geocoders

| Variable | Default | Effect |
|---|---|---|
| `SITEPLAIN_GEOCODER_USER_AGENT` | `site-plain/0.0.1 (+https://site-plain.com/feedback)` | Sent to Census and Nominatim. Nominatim's policy wants a contact in it |
| `SITEPLAIN_GEOCODER_CENSUSBASEURL` | `https://geocoding.geo.census.gov/geocoder/locations/onelineaddress` | Census endpoint (checked) |
| `SITEPLAIN_GEOCODER_CENSUSBENCHMARK` | `Public_AR_Current` | Census benchmark |
| `SITEPLAIN_GEOCODER_CENSUSTIMEOUT` | `3s` | Census request timeout |
| `SITEPLAIN_GEOCODER_NOMINATIMBASEURL` | `https://nominatim.openstreetmap.org/search` | Nominatim endpoint (checked) |
| `SITEPLAIN_GEOCODER_NOMINATIMTIMEOUT` | `5s` | Nominatim request timeout |
| `SITEPLAIN_GEOCODER_NOMINATIMLIMIT` | `1` | Results asked from Nominatim; the first is used |
| `SITEPLAIN_GEOCODER_CACHEMAXENTRIES` | `5000` | Geocode cache size (per instance, in memory) |
| `SITEPLAIN_GEOCODER_CACHETTL` | `7d` | How long a match is reused |
| `SITEPLAIN_GEOCODER_CACHEUNRESOLVEDTTL` | `1h` | How long a definite no-match from both geocoders is reused |

### Limits

| Variable | Default | Effect |
|---|---|---|
| `SITEPLAIN_LIMITS_RATELIMITSENABLED` | `true` | `false` turns off the per-client buckets; the global caps stay (checked) |
| `SITEPLAIN_LIMITS_CLIENTIPHEADER` | `X-Real-IP` | Header that names the client for the buckets |
| `SITEPLAIN_LIMITS_MAXTRACKEDCLIENTS` | `20000` | Clients tracked per bucket; the least recently seen is dropped past it |
| `SITEPLAIN_LIMITS_<BUCKET>_CAPACITY`, `SITEPLAIN_LIMITS_<BUCKET>_REFILL` | see [rate limits](#rate-limits-and-global-caps) | Per-client token bucket; `<BUCKET>` is `LOOKUP`, `RESULTS`, `PDF`, `FEEDBACK`, `SUGGESTIONS` or `SEARCH` (checked with `FEEDBACK_CAPACITY`) |
| `SITEPLAIN_LIMITS_<CAP>_MAX`, `SITEPLAIN_LIMITS_<CAP>_MAXWAIT` | see [rate limits](#rate-limits-and-global-caps) | Global concurrency cap; `<CAP>` is `PDFRENDERING`, `GEOCODING` or `PROXIMITYQUERY`. `MAX` must be at least 1 |
| `SITEPLAIN_LIMITS_NOMINATIMINTERVAL` | `1s` | Least time between two Nominatim calls, app-wide |
| `SITEPLAIN_LIMITS_NOMINATIMMAXWAIT` | `3s` | Longest a lookup waits for its Nominatim turn before answering 503 |

### Logging

| Variable | Default | Effect |
|---|---|---|
| `SITEPLAIN_LOGGING_CLIENTERRORINTERVAL` | `60s` | Client-error WARN lines (4xx) are logged at most once per interval per status and exception type, with a count (checked with `0s`) |

### Platform

| Variable | Effect |
|---|---|
| `PORT` | Set by Railway; the server port |
| `JAVA_OPTS` | JVM flags, read by the start command ([JAVA_OPTS](#java_opts)) |
| `RAILWAY_HEALTHCHECK_TIMEOUT_SEC` | Healthcheck timeout; set `600` ([healthcheck](#healthcheck)) |
| `NIXPACKS_JDK_VERSION` | Set on the service today but the builder is Railpack; whether Railpack reads it is unverified. The build log should show JDK 21 |

---

## Results token key

`POST /lookup` redirects to `/results?r=<token>`: the address, coordinates and geocoder answer, encrypted and authenticated with AES-256-GCM under `SITEPLAIN_RESULTS_TOKEN_KEY`. The address never reaches the URL, browser history or analytics, and a changed or made-up link is refused instead of rendering a report the lookup never produced. Links do not expire.

Generate (once) and set:

```bash
openssl rand -base64 32          # 44 characters ending in "="
railway variable set SITEPLAIN_RESULTS_TOKEN_KEY=<that value> --service site-plain
```

Or without the value touching shell history: `openssl rand -base64 32 | railway variable set SITEPLAIN_RESULTS_TOKEN_KEY --stdin --service site-plain`.

- **Back it up** in a password manager. It is the one secret that cannot be recreated: a new key is a rotation.
- **Rotation or loss**: every results link made with the old key stops working. Opening one gives a 400 "Request not valid" page (log: `Client error 400 on GET /results (BadRequestException: results link not valid)`, throttled). New lookups work at once. Nothing is stored server side, so there is nothing to migrate. Rotate only if the key leaked.
- **Unset**: the app starts with a random key and logs WARN `SITEPLAIN_RESULTS_TOKEN_KEY is not set: using a random key, so results links made now stop working when the app restarts`. Never leave it unset in production.
- **Malformed**: startup fails (`SITEPLAIN_RESULTS_TOKEN_KEY must decode to 32 bytes, not 16`, or `... is not valid base64`), the deployment never turns ready and the old one keeps serving.
- Links from before encrypted links (`/results?lat=...&lng=...`) still render, showing only the coordinates. The pre-October image ignores `r=` and shows "Address could not be resolved" for new links ([data README 5.2](data/README.md#52-app-rollback)).

---

## Security headers and CSP

Every response (pages, static files, JSON, the PDF, redirects, error pages, probes) carries:

| Header | Value |
|---|---|
| `Content-Security-Policy` | `default-src 'self'`; scripts from self, `www.googletagmanager.com`, `api.mapbox.com`, `static.cloudflareinsights.com`; styles from self, `fonts.googleapis.com`, `cdn.jsdelivr.net`, `api.mapbox.com`; fonts from `fonts.gstatic.com`; images and connections to self, Google Analytics, `www.google.com` and Mapbox; connections also to `cloudflareinsights.com`; `worker-src blob:`; `object-src 'none'`; `base-uri 'self'`; `form-action 'self'`; `frame-ancestors 'none'`; `report-uri /csp-report`. No `unsafe-inline`, no `unsafe-eval`. The exact string is `SecurityHeadersFilter.CONTENT_SECURITY_POLICY` |
| `Strict-Transport-Security` | `max-age=31536000` (HTTPS requests only; no `includeSubDomains`, no preload) |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Permissions-Policy` | `accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()` |
| `X-Robots-Tag` | `noindex`, only on `/results`, `/results/pdf` and their error and busy responses (see [owner action 7](#owner-actions)) |

Check from anywhere: `curl -sI https://site-plain.com/ | grep -iE 'content-security|strict-transport|x-frame|referrer|permissions|x-content'`.

**Cloudflare edge-injected scripts.** Cloudflare Web Analytics (cookieless) injects its beacon into every HTML page at the edge. The policy allows it, per Cloudflare's documented CSP requirements: the script from `https://static.cloudflareinsights.com` (`script-src`) and its posts to `https://cloudflareinsights.com/cdn-cgi/rum` (`connect-src`). Other Cloudflare features that inject or rewrite scripts, such as Rocket Loader and Zaraz, are still blocked by the policy: leave them off, or extend the policy first.

**CSP mode switch.** If production shows the policy blocking something it should not (a broken map, missing analytics), switch to report-only without a code change:

```bash
railway variable set SITEPLAIN_SECURITY_CSP_MODE=report-only --service site-plain     # deploys
```

The header becomes `Content-Security-Policy-Report-Only`: nothing is blocked, violations are still reported. Fix the policy in `SecurityHeadersFilter`, deploy, then delete the variable (back to `enforce`).

**Reading `/csp-report` lines.** Browsers post violations to `/csp-report`, which logs one WARN line per directive per minute: `Content-Security-Policy blocked <directive> for <origin> on <path> (<n> since the last report)` (or `would block` in report-only mode). The endpoint is unauthenticated: anyone can post a fake report, and a flood of fakes can hide a real one inside a directive's per-minute count. Treat each line as a hint and reproduce it in a browser before loosening the policy or calling it an attack. Mapbox tiles and telemetry under the policy were checked in a local browser only; confirm the results map on prod after the first deploy.

---

## Rate limits and global caps

Two layers. **Per-client token buckets** on the public endpoints, keyed on the client IP (`X-Real-IP`): a bucket holds `capacity` requests and gets one back every `refill`. Refused requests get 429 with `Retry-After` and the plain busy page. **Global caps** on the expensive work, whoever asks: past `max` running at once, a request waits up to `maxWait`, then gets 503 with `Retry-After: 5`.

| Bucket (`SITEPLAIN_LIMITS_<BUCKET>_...`) | Endpoint | Capacity | Refill | So, per client |
|---|---|---|---|---|
| `LOOKUP` | `POST /lookup` | 30 | `3s` | 30 at once, then 20 a minute |
| `RESULTS` | `GET /results` | 60 | `1s` | 60, then 60 a minute |
| `PDF` | `GET /results/pdf` | 10 | `6s` | 10, then 10 a minute |
| `FEEDBACK` | `POST /feedback` | 5 | `12m` | 5, then 5 an hour |
| `SUGGESTIONS` | `GET /api/site-search/suggestions` | 60 | `500ms` | 60, then 120 a minute |
| `SEARCH` | `GET /search/sites` | 30 | `2s` | 30, then 30 a minute |

`HEAD` counts as `GET`. The bucket is chosen by the path Spring matched, so `/feedback;x=y` and encoded variants share the bucket.

| Cap (`SITEPLAIN_LIMITS_<CAP>_...`) | Guards | `MAX` | `MAXWAIT` |
|---|---|---|---|
| `PDFRENDERING` | PDF rendering | 2 | `2s` |
| `GEOCODING` | One uncached lookup's Census and Nominatim calls | 6 | `1s` |
| `PROXIMITYQUERY` | The nearby-sites query (lookup, results page, PDF) | 6 | `2s` |

Plus the Nominatim gate: one call per `SITEPLAIN_LIMITS_NOMINATIMINTERVAL` (1 s) app-wide, waiting at most `SITEPLAIN_LIMITS_NOMINATIMMAXWAIT` (3 s).

Log lines (each at most once a minute per name, with a count, never with the client address): `Rate limit refused <bucket> requests (<n> since the last report)` and `Refused <cap>: all <n> slots busy (<n> since the last report)`.

Tuning: set the variable and redeploy, for example `railway variable set SITEPLAIN_LIMITS_RESULTS_CAPACITY=120 --service site-plain`. Raise a cap only with memory headroom: the proximity cap is what keeps a burst of very large results pages from exhausting the heap (see [JAVA_OPTS](#java_opts)), and the database pool is 10 connections.

**If `X-Real-IP` collapses visitors into one bucket.** Railway's edge sets `X-Real-IP` to the client address (from Cloudflare's `CF-Connecting-IP` when the request comes through Cloudflare) and overwrites any copy a client sends. That is Railway behavior, not a contract, and Railway has shipped a regression where it held a CDN address. Symptom: many `Rate limit refused ...` lines while traffic is ordinary, and real visitors getting 429 pages. Do, in order:

1. Turn the per-client limits off: `railway variable set SITEPLAIN_LIMITS_RATELIMITSENABLED=false --service site-plain`. The global caps still bound cost and load.
2. Find out what the edge sees (`railway logs --service site-plain --http --lines 50`; HTTP log entries carry the source IP as `@srcIp`), and report it to Railway.
3. Turn the limits back on once fixed. Do not switch `SITEPLAIN_LIMITS_CLIENTIPHEADER` to `CF-Connecting-IP`: anyone who reaches the Railway origin directly can set that header to anything.

`POST /lookup` also refuses cross-site posts: it needs `Sec-Fetch-Site: same-origin`, or, from a browser that sends no `Sec-Fetch-Site`, an `Origin` equal to the site's own; anything else is a 403 with no geocoding and no audit row (log: `Client error 403 on POST /lookup (ForbiddenException: lookup not posted from this site)`). An uptime probe that POSTs `/lookup` with plain curl gets 403; probe the readiness path instead. The lookup and feedback forms carry a hidden `website` honeypot field; a filled one gets the generic page (lookup) or the thank-you page (feedback) with nothing geocoded or stored.

---

## Healthcheck

Set on the `site-plain` service in Railway (owner action):

- **Healthcheck Path:** `/actuator/health/readiness`
- **Healthcheck Timeout:** 600 s (service setting, or `RAILWAY_HEALTHCHECK_TIMEOUT_SEC=600`)

Readiness turns 200 only after the startup data refresh has returned ([data README 2.9](data/README.md#29-readiness)), so Railway keeps the old deployment serving until the new one has its data, and never switches to one that fails to start. Railway calls the container directly over plain HTTP with its own host name; the probe paths are exempt from the canonical-host redirect for that reason. A routine deploy is ready about 2 s after the JVM starts; a boundary reload adds 4 to 10 s. The 600 s timeout covers the worst case, a boundary download that hangs for its full 300 s timeout (the app then serves the old data and turns ready).

Caveat: the image before the October 2026 release has no readiness path and redirects every path on the healthcheck's host, so a rollback to it never passes this healthcheck. Clear the path before such a rollback ([data README 5.2](data/README.md#52-app-rollback)).

---

## Deploying and what to watch

Deploy a committed, clean tree, and tag the deployment with the commit:

```bash
git status --short           # nothing but untracked tooling (.claude/)
railway up --service site-plain --message "$(git rev-parse --short HEAD)"
```

`railway up` uploads the working directory (honoring `.gitignore`), so uncommitted edits would ship. Railway builds remotely; no local `mvn package` is needed. A variable change also starts a new deployment of the current build.

In the deploy logs, in order (`railway logs --service site-plain --deployment --lines 300`):

1. Flyway: `Successfully applied <n> migrations ...` on a release with a migration, otherwise `Schema "public" is up to date. No migration necessary.`
2. `Started SitePlainApplication in <n> seconds`.
3. `NPL boundaries: KEEP|RELOAD|ADOPT (<reason>)`, `Downloaded Human exposure feed from ...`, then `Data refresh done in a <n> ms swap. Boundaries ..., exposure .... SEO pages: 1337, State pages: 53`. The two page counts should match the previous deploy unless EPA changed the data.

Act on:

| Line | Meaning |
|---|---|
| WARN `SITEPLAIN_RESULTS_TOKEN_KEY is not set` | Set the key now; links made since this start will break at the next restart |
| WARN `SITEPLAIN_DATA_MAX_AGE_DAYS=... is ignored` | Delete the variable |
| ERROR `... refresh failed; keeping the live data: <reason>` | A source failed; see [data README 5.3](data/README.md#53-dead-or-broken-url) |
| WARN `Data refresh skipped: another instance holds the refresh lock` | Deployed during the old instance's refresh; restart if a boundary change was pending |
| `Application run failed`, `OutOfMemoryError` | The deployment will not go live; read the cause |
| ERROR `Unhandled application error` | A real 500, with stack trace |
| WARN `Refused proximity query: all 6 slots busy`, `Rate limit refused ...` | Load or abuse; occasional lines are normal, a steady stream is not |
| WARN `Client error <status> on <method> <path> (...)` | Client mistakes, throttled to one line a minute per kind |
| WARN `Content-Security-Policy blocked ...` | See [CSP](#security-headers-and-csp) |

After a deploy: the live-site checks in [data README section 6](data/README.md#6-verification), one lookup in a browser, the results map loading, and Railway's memory graph over the next day.

---

## JAVA_OPTS

Recommended:

```bash
railway variable set "JAVA_OPTS=-Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1" --service site-plain     # deploys
```

From the October 2026 cost runs (Linux arm64 container limited to 8 GB, prod profile, prod data):

| Setting | Steady memory (RSS) | Results p50 / p95 | PDF p50 / p95 |
|---|---|---|---|
| No `JAVA_OPTS` (G1, 2 GB max heap) | 390 to 480 MB, still growing (prod measures 0.65 GB) | 6.3 / 10.3 ms | 10.5 / 17.0 ms |
| `-Xmx256m -XX:+UseSerialGC` | about 390 MB | 6.9 / 14.7 ms | 11.0 / 22.6 ms |
| Recommended | 250 to 270 MB (267 MB over 8,152 requests) | 8.2 / 12.1 ms | 10.2 / 16.8 ms |

- **Why each flag.** `-Xmx256m` caps the heap (the default lets G1 grow toward 2 GB and keep it). SerialGC has the smallest footprint, which suits one small service. `-XX:TieredStopAtLevel=1` (C1 compiler only) cuts the code cache and compiler memory by about 120 MB, costs about 1.5 to 2 ms on results p50, and uses less CPU at this traffic. Warm boot to ready is faster (2.1 to 2.4 s against 2.9 s).
- **Headroom.** A boundary reload peaks at 40 to 60 MB of live heap. The real heap driver is a burst of results pages for the largest sites (NASA Wallops is 7.9 MB of GeoJSON): `-Xmx128m` ran out of memory at 16 concurrent such pages and `-Xmx160m` at 200; 192m and 256m survived 200, with the proximity cap shedding the excess as 503s. Do not go below 192m.
- **Cost.** At $10 per GB-month, app memory goes from about $6.50 to about $3.00 a month. Without the C1 flag (`-Xmx256m -XX:+UseSerialGC`) it is about $1.20 a month more.
- **After setting it**, watch for 24 to 48 hours: Railway memory should settle around 0.25 to 0.32 GB (look into anything above 0.40 GB), no `OutOfMemoryError`, few `Refused proximity query` lines, results latency steady. If CPU or latency rises noticeably, drop `-XX:TieredStopAtLevel=1`.
- **Rollback caution.** The image before the October 2026 release buffers the whole boundary file and needs about 384 MB of heap to reload it. Keep it from reloading during a rollback ([data README 5.2](data/README.md#52-app-rollback)) or remove `JAVA_OPTS` for it.
- Not covered by the measurements: days of uptime (creep is extrapolated) and amd64 (absolute numbers may differ by 10 to 15%).

Leave the Hikari pool (10) and Postgres settings alone: each is worth under $0.30 a month, and a pool smaller than the caps would only queue.

---

## Backups

With no Postgres volume today, a backup is the only protection for `lookup_audit` and `feedback`. Take one before any Postgres change and on a schedule you can keep (monthly at least). It needs a way to reach the database from your machine: the `postgres-postgis` TCP proxy (enable it, back up, disable it again), with the connection details from the Postgres service's variables.

```bash
PGPASSWORD='<password>' pg_dump -Fc -h <proxy host> -p <proxy port> -U postgres -d railway \
  -f siteplain-prod-$(date +%F).dump        # about 17 MB, a few seconds
```

Restore into an empty database (a new Postgres, or a scratch database to inspect it):

```bash
createdb -h <host> -p <port> -U postgres <db>
PGPASSWORD='<password>' pg_restore --no-owner -h <host> -p <port> -U postgres -d <db> siteplain-prod-YYYY-MM-DD.dump
```

`pg_restore` 17 or newer against a 16 server prints one ignorable error, `unrecognized configuration parameter "transaction_timeout"`. After restoring prod, restart the app so it runs Flyway and its startup refresh against the restored data. Keep the dumps out of the repository and treat them as sensitive (they hold feedback text). Checked locally: dump, restore and the app's boot on the restored database.

---

## Running locally and tests

PostgreSQL with PostGIS in Docker, then the jar against it with a local copy of the sources:

```bash
docker run -d --name siteplain-pg -e POSTGRES_PASSWORD=localdev -p 55432:5432 postgis/postgis:16-3.4
docker exec siteplain-pg createdb -U postgres siteplain_local
export JAVA_HOME=$(/usr/libexec/java_home -v 21)       # macOS; any JDK 21 elsewhere
mvn -q -DskipTests package
SPRING_PROFILES_ACTIVE=prod SITEPLAIN_SITE_ENFORCECANONICALHOST=false \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/siteplain_local \
SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=localdev \
SITEPLAIN_DATA_NPL_BOUNDARIES_URL=https://github.com/manojbade/site-plain/releases/download/data-2026-07-12/npl_site_boundaries.geojson \
SITEPLAIN_RESULTS_TOKEN_KEY="$(openssl rand -base64 32)" \
java -jar target/site-plain-0.0.1-SNAPSHOT.jar --server.port=18080
```

The first boot migrates the empty database and loads both sources (a few seconds plus the downloads); then open `http://localhost:18080/`. A `file:` URL or a plain path also works for `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` and `SITEPLAIN_DATA_HUMAN_EXPOSURE_URL`, which avoids downloads in drills. `SITEPLAIN_GEOCODER_CENSUSBASEURL` and `SITEPLAIN_GEOCODER_NOMINATIMBASEURL` can point at a local stub to keep lookups off the public geocoders.

Full test suite (JDK 21 only; JDK 25 breaks Mockito), against a separate database:

```bash
docker exec siteplain-pg createdb -U postgres siteplain_test
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
TEST_DATASOURCE_URL=jdbc:postgresql://localhost:55432/siteplain_test \
TEST_DATASOURCE_USERNAME=postgres TEST_DATASOURCE_PASSWORD=localdev mvn -q test
```

Without `TEST_DATASOURCE_URL` the integration tests are skipped, so always set it before trusting a green run.

---

## Owner actions

Railway and third-party settings the code cannot make. Each needs the owner.

1. **Postgres volume.** `postgres-postgis` keeps its data on the container's own disk: any restart or redeploy of Postgres loses the database, and with it `lookup_audit` and `feedback` (site data reloads from the sources). Fix in a short window: take a backup ([Backups](#backups)) and check its row counts; attach a volume to `postgres-postgis` at `/var/lib/postgresql/data` and set `PGDATA=/var/lib/postgresql/data/pgdata` on it (so initdb does not trip over `lost+found`); after Postgres comes back empty, restore the dump; restart the app. Check first whether the unattached project volumes `postgres-volume` and `postgres-volume-Tomf` hold older data. Cost is a few cents a month.
2. **Database password rotation.** The app connects as the `postgres` superuser, and that password has been stored on a development machine. Rotate it: change the password on `postgres-postgis` (and in its `POSTGRES_PASSWORD`), set the same value in the app's `SPRING_DATASOURCE_PASSWORD` with `--skip-deploys`, then redeploy the app; a mismatch means a short outage, so do both together. Later: a separate least-privilege role for the app.
3. **TCP proxy.** The Postgres TCP proxy is public and the server runs with `ssl=off`, so a session over it is plaintext. The app does not use it (private network). Keep it disabled. Prod data checks do not need it: verify loads from the deploy logs ([data README 6.1](data/README.md#61-prod-check-from-the-deploy-logs)). Enable it only for a backup or for an optional SQL check, and disable it again in the same sitting ([data README 6.2](data/README.md#62-sql-checks)).
4. **Healthcheck.** Path `/actuator/health/readiness`, timeout 600 s ([Healthcheck](#healthcheck)).
5. **Variables.** Set `SITEPLAIN_RESULTS_TOKEN_KEY` (and back it up) and `JAVA_OPTS`; delete `SITEPLAIN_DATA_MAX_AGE_DAYS`; if `SITEPLAIN_GEOCODER_USER_AGENT` is set, unset it or make sure it carries a contact URL.
6. **Google Analytics.** Property G-0574GW6TCV (named "tap-truth") also collects emissionmap.com and tap-truth.com, so any Site Plain number must come from a report filtered to hostname `site-plain.com`; better, give Site Plain its own property or data stream. Results URLs from before encrypted links sent typed addresses and coordinates to GA in `page_location`: in the web data stream's Redact data settings, redact the query parameters `address`, `raw`, `lat`, `lng` and `r`, and file a data deletion request for the old URL data. The current code already strips the query string from `/results` page views.
7. **Google Search Console.** Verify `site-plain.com` and submit `https://site-plain.com/sitemap.xml`. Then deal with results URLs indexed before the October 2026 release; the old ones carried typed addresses in their query strings (owner action 6).
   - **The permanent fix is already in the code.** Every response under `/results` (page, PDF, error pages) says `noindex`, in a meta tag and in an `X-Robots-Tag` header, and `robots.txt` deliberately does not disallow `/results`. Google drops each such URL the next time it crawls it and sees the noindex. Check with `curl -sI "https://site-plain.com/results?lat=39.8&lng=-84.0" | grep -i x-robots-tag` (expect `X-Robots-Tag: noindex`).
   - **Removals is only a stopgap.** A Removals request with the prefix `https://site-plain.com/results` hides those URLs from Google results at once, but for about six months only; it does not de-index anything. Use it if you want them gone before Google recrawls, and do not treat it as the fix.
   - **Do not add `Disallow: /results` to `robots.txt`** while any results URL is still indexed: a crawler that may not fetch a page never sees its noindex, so those URLs would stay indexed (as URL-only entries) for good. In Search Console's Pages report, results URLs should move to "Excluded by 'noindex' tag" and then disappear; only after none is indexed would a `Disallow` be harmless, and even then it adds nothing.
8. **Mapbox token.** `MAPBOX_PUBLIC_TOKEN` is public by design (it is in the page). In the Mapbox account, restrict it to the URL `https://site-plain.com`, keep only the public scopes it needs (maps and geocoding), and set a usage alert, so a copied token cannot run up charges.
