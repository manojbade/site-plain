# Site Plain — Superfund Site Proximity Lookup
**Project:** Site Plain
**Type:** Civic tech / NIW portfolio tool
**Status:** Pre-build — spec complete
**Domain:** site-plain.com (to be registered)
**Last updated:** 2026-03-22

---

## The Problem

19.46 million Americans live within 1 mile of an active EPA National Priorities List (NPL) Superfund site. These are the most contaminated places in the country — legacy industrial sites, smelters, wood preserving facilities, military bases, chemical plants. EPA tracks their boundaries, contamination types, and whether human exposure is currently under control.

The information is public. No consumer tool surfaces it at the address level.

EPA removed EJScreen — the main proximity tool used by communities, advocates, and researchers — on February 5, 2025. H.R. 6815 was introduced in the 119th Congress to restore it. No federal replacement has been published.

**Federal documentation of the gap:**
- EPA OIG Report 20-N-0231 (2020): EPA "lacks a nationally consistent strategy for communicating health risks to the public at contaminated sites." Direct federal citation of the information access gap.
- EPA Superfund Community Involvement Handbook (2016): Technical information "must be translated into language that community members can understand." Federal recognition of the translation duty.
- EPA Human Exposure Dashboard: Created specifically to "improve public access" to site-status information — confirming the access problem existed before this tool closes it.

**Who is most affected:**
- 50.2% of the population within 1 mile of Superfund sites is minority (vs. 41.1% nationally)
- 14.1% below poverty (vs. 12.7% nationally)
- 6.9% linguistically isolated (vs. 4.8% nationally)
- Source: EPA Office of Land and Emergency Management, "Population Surrounding 1,881 Superfund Sites" (2023)

**Scale:**
- 19.46 million people within 1 mile of 1,328 active/final NPL sites (spatial audit, 2026-03-22)
- 357 of 435 congressional districts contain at least one active NPL site
- 49 states affected
- 571 sites (43%) are in rural Census-classified areas

---

## The Tool

**Name:** Site Plain
**Tagline:** What's on the EPA priority list near your home?
**URL:** https://site-plain.com

**One-sentence pitch:** Enter your address and see every active EPA Superfund site within 3 miles — distance to the site boundary and whether human exposure is currently under control, in plain English.

---

## User Flow

### Address Lookup (primary path)
1. User enters a home address
2. Tool geocodes the address (Census Geocoder → Nominatim fallback)
3. PostGIS finds all active NPL site boundaries within 3 miles of lat/lng, ordered by distance
4. Results page shows each site with: distance to boundary, human exposure status (plain English), and EPA exposure pathway description when available

### No results case
- If no active NPL sites within 3 miles: show "No active Superfund sites found within 3 miles of this address" + national context stat
- Do NOT show this as a green "you're safe" — the absence of a nearby listed site does not mean the land is clean

### Result display

Results are shown as a flat list ordered by distance ascending (nearest first). Every site uses the same card layout regardless of distance. The distance number is the signal — the tool does not interpret proximity for the user.

Each site card shows:
- Site name (linked to `/site/{epa_id}`)
- Distance to site boundary (e.g. "340 feet", "0.4 miles", "2.1 miles")
- Human exposure status — color-coded label (Red/Yellow/Green/Gray) + one plain-English sentence
- Exposure pathway description — if available from EPA: "About this site's exposure pathways: [EPA text]"
- Link to EPA site profile

---

## Architecture

**Stack:** Java 21 / Spring Boot 3.x / Thymeleaf / Bootstrap 5 / Maven / PostgreSQL (PostGIS) / Railway

### 1. Address → NPL Site Lookup

```
User enters address
       ↓
Geocode → lat/lng
(Census Geocoder → Nominatim fallback)
       ↓
PostGIS ST_DWithin: find all NPL site polygons within 3 miles (4,828m)
       ↓
For each site: ST_Distance to compute exact distance to boundary polygon
       ↓
Join: human exposure status
       ↓
Results page — ordered by distance ascending
```

**Key PostGIS query:**
```sql
SELECT
  s.epa_id,
  s.site_name,
  s.state_code,
  ST_Distance(
    s.geom::geography,
    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
  ) AS distance_meters,
  h.humexposurestscode,
  h.humanexposurepathdesc
FROM npl_site_boundaries s
LEFT JOIN npl_human_exposure h ON h.epa_id = s.epa_id
WHERE ST_DWithin(
  s.geom::geography,
  ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
  4828.032  -- 3 miles in meters
)
ORDER BY distance_meters ASC
```

**Spatial index (required):**
```sql
CREATE INDEX idx_npl_boundaries_geom ON npl_site_boundaries USING GIST(geom);
```

### 2. Human Exposure Status — Plain Language

| EPA Code | Plain-language label | UI color |
|---|---|---|
| HENC | Human exposure is NOT currently under control | 🔴 Red |
| HEUC | Human exposure is under control | 🟡 Yellow |
| HEPR | Human exposure is under control, protective remedies in place | 🟡 Yellow |
| HHPA | Long-term health protection has been achieved | 🟢 Green |
| HEID | Exposure status: insufficient data | ⬜ Gray |
| (null/missing) | Exposure status not reported | ⬜ Gray |

**Important:** Red/yellow/green is about exposure control status, NOT about whether the site is dangerous. This distinction must be shown clearly in the UI and in the About page methodology.

### 3. Distance Display

| Distance | Display |
|---|---|
| < 528 feet (0.1 mi) | "Less than 0.1 miles (inside or immediately adjacent to site boundary)" |
| 528–2,640 feet | "X feet (0.X miles)" |
| > 0.5 miles | "X.X miles" |

Distance is to the **site boundary polygon**, not to a site centroid. This is the key technical difference from naive radius searches — it means a user whose address is physically adjacent to the site gets 0 feet, not 0.5 miles to a centroid.

### 4. Contamination Type — Phase 2 Only

The NPL boundary GDB has no contamination type field (confirmed in pre-build verification). Phase 1 does not show contamination type. Phase 2 will source it from SEMS/CERCLIS.

Phase 1 shows only: distance to boundary + human exposure status + exposure pathway description (from `humanexposurepathdesc`).

---

## Data Sources

| Source | What it provides | Format | How loaded |
|---|---|---|---|
| EPA NPL Site Boundaries | True MultiPolygon geometry per NPL site, EPA_ID, state, NPL status code | GDB (geodatabase) / GeoJSON | Downloaded once, loaded into PostGIS `npl_site_boundaries`. Annual refresh. |
| EPA Human Exposure Site List | `humexposurestscode` per EPA_ID, site name, state | JSON (live feed) | Downloaded at startup, loaded into `npl_human_exposure`. Refresh with each deploy. |
| EPA NPL OU Boundaries | Operable unit sub-boundaries (239 polygons) | GDB / GeoJSON | Phase 2 — not Phase 1 |
| Census Geocoder | Address → lat/lng | REST API | Live call per request |
| Nominatim (OSM) | Geocoder fallback | REST API | Live call on Census failure |

**Data source URLs:**
- NPL Site Boundaries: https://catalog.data.gov/dataset/npl-superfund-site-boundaries-epa10
- Human Exposure JSON: https://www3.epa.gov/semsjson/Human_Exposure_Site_List.json
- NPL OU Boundaries: https://catalog.data.gov/dataset/npl-superfund-operable-unit-boundaries-epa10

**Loading strategy:**
- NPL boundaries: downloaded manually, uploaded to GitHub releases, loaded at startup from env var URL. NOT downloaded live per request.
- Human exposure JSON: small file (~500KB), downloaded at startup. Refreshed on each deploy.
- No live EPA API calls in the request path. All data is in local PostgreSQL.

---

## Database Schema

```sql
-- PostGIS extension (required)
CREATE EXTENSION IF NOT EXISTS postgis;

-- NPL site boundary polygons
CREATE TABLE npl_site_boundaries (
  epa_id              varchar(20) PRIMARY KEY,
  site_name           varchar(255) NOT NULL,
  state_code          varchar(2),
  npl_status_code     varchar(10),           -- 'F' = final NPL
  epa_url             text,                  -- URL_ALIAS_TXT from GDB (nullable)
  -- contamination_type: no field in source GDB — not in schema
  -- npl_listing_date: no field in source GDB — not in schema
  geom                geometry(MultiPolygon, 4326) NOT NULL,
  loaded_at           timestamp NOT NULL DEFAULT now()
);

CREATE INDEX idx_npl_boundaries_geom ON npl_site_boundaries USING GIST(geom);
CREATE INDEX idx_npl_boundaries_state ON npl_site_boundaries(state_code);
CREATE INDEX idx_npl_boundaries_status ON npl_site_boundaries(npl_status_code);

-- Human exposure status per site (joined from EPA JSON feed)
CREATE TABLE npl_human_exposure (
  epa_id                  varchar(20) PRIMARY KEY REFERENCES npl_site_boundaries(epa_id),
  humexposurestscode      varchar(10),       -- HENC, HEUC, HEPR, HHPA, HEID
  humanexposurepathdesc   text,              -- free text, exposure pathway description
  npl_status              varchar(5),        -- F, D, P
  site_name               varchar(255),
  loaded_at               timestamp NOT NULL DEFAULT now()
);

-- SEO page cache — precomputed at load time, served at request time without DB join
CREATE TABLE site_seo_page_cache (
  epa_id                  varchar(20) PRIMARY KEY REFERENCES npl_site_boundaries(epa_id),
  site_name               varchar(255) NOT NULL,
  state_code              varchar(2),
  exposure_status_code    varchar(10),
  exposure_status_label   varchar(200),      -- plain-language status
  exposure_pathway_desc   text,
  epa_url                 text,              -- copied from npl_site_boundaries.epa_url
  -- contamination_summary: no data source — not in schema
  -- npl_listing_date: no data source — not in schema
  computed_at             timestamp NOT NULL DEFAULT now()
);

-- Lookup audit log (analytics — from day 1)
CREATE TABLE lookup_audit (
  id              bigserial PRIMARY KEY,
  lookup_at       timestamp NOT NULL DEFAULT now(),
  state_code      varchar(2),                -- state of the queried address
  result_count    integer,                   -- how many sites returned
  nearest_miles   numeric(8,3),             -- distance to nearest site if any
  geocoder_used   varchar(20),              -- 'census' or 'nominatim'
  resolved        boolean NOT NULL          -- did lookup succeed?
);
```

---

## Pages

### `/` — Landing Page

- Single address input, prominent
- Tagline: "What's on the EPA priority list near your home?"
- Stat: "19.4 million Americans live within 1 mile of an active Superfund site"
- Secondary stat: "357 of 435 congressional districts have at least one active Superfund site"
- Data source credit: EPA National Priorities List, updated [date]
- No letter grades, no alarm language — factual, calm, civic tone

**DO NOT on the landing page:**
- Do not say "Is your neighborhood contaminated?" — this implies the tool answers that question. It does not.
- Do not use red color on the landing page before results — no pre-alarming

### `/results` — Lookup Results Page

**If sites found:**
- Address confirmed (from geocoder response)
- For each site (ordered by distance):
  - Site name (linked to `/site/{epa_id}`)
  - Distance to boundary (precise to feet if < 0.5 miles)
  - Human exposure status — color-coded label + one plain-English sentence
  - Exposure pathway description — if available: "About this site's exposure pathways: [EPA text]"
  - Link: "View EPA site profile →" (uses site's EPA URL when available, falls back to EPA site search)
- Disclaimer block (see Disclaimer section below)
- Feedback link

**If no sites within 3 miles:**
- "No active EPA Superfund sites found within 3 miles of this address."
- Do not say "your area is clean" or "you're safe"
- Show: "The EPA National Priorities List includes 1,328 active sites nationally. Absence from this list does not mean an area is free from contamination."
- Feedback link

### `/site/{epa_id}` — Static SEO Page (from day 1, not Phase 2)

One page per active NPL site. Indexable by Google. Example: `/site/GA8213820731`

**Why from day 1:** Tap Truth shipped this as an afterthought and had 1 page indexed for weeks. Site Plain ships with static pages on day 1 so Google can index immediately.

**Each page contains:**
- Site name as H1
- State, EPA ID
- Human exposure status — color-coded, plain language
- Exposure pathway description — if available from EPA
- "Enter your address to see how close you are to this site" — address box wired into the normal lookup flow
- Link to EPA SEMS profile for the site
- Disclaimer block

**URL:** `/site/{epa_id}` — EPA ID is the canonical identifier (e.g. `GA8213820731`)
**Title tag:** `[Site Name] — EPA Superfund Site | Site Plain`
**Meta description:** `[Site Name] is an active EPA Superfund site in [State]. Human exposure status: [status]. Enter your address to see your distance from this site.`
**Canonical:** `/site/{epa_id}`
**robots:** `index, follow`

**Sitemap:** `/sitemap.xml` — dynamically generated, includes all `/site/{epa_id}` pages that return 200, plus `/`, `/about`. Submit to Google Search Console immediately on first deploy.

**SEO target queries:**
- "[city name] Superfund site"
- "[site name] EPA"
- "Superfund sites near [city]"
- "EPA cleanup site [state]"
- "is there a Superfund site near me"

### `/about` — Methodology and Limitations

Full explanation of:
- What the EPA National Priorities List is
- What human exposure control status means (and what it does NOT mean)
- Why proximity ≠ exposure ≠ health risk
- Why site boundary ≠ groundwater plume (the key data limitation)
- Data sources and update frequency
- Who built this and why (NIW context, not promotional)

### `/state/{code}` — State Index Pages (Phase 1)

One page per state with at least one active NPL site. Example: `/state/CA`, `/state/NJ`.

**Each page contains:**
- H1: "EPA Superfund Sites in [State Name]"
- Count of active NPL sites in the state
- Full list of sites: site name (linked to `/site/{epa_id}`) + exposure status badge
- "Enter your address to find sites near you" — address input wired into the lookup flow

**SEO targets:** "EPA Superfund sites in California", "Superfund sites in New Jersey", etc.

**Title tag:** `EPA Superfund Sites in [State] ([N] active sites) | Site Plain`
**URL:** `/state/{two-letter-code}` (uppercase-normalized)
**robots:** `index, follow`

Served from two precomputed tables: `state_page_cache` (state summary — name, site count) and `site_seo_page_cache` (site list — queried live by state_code at request time, but site_seo_page_cache is fully precomputed so no expensive join).

### `/sitemap.xml`

Precomputed at startup, stored in memory. Includes:
- `/`
- `/about`
- `/site/{epa_id}` for every active final NPL site
- `/state/{code}` for every state with at least one active site

---

## What This Tool Does NOT Do

- Does not test soil, water, or air quality
- Does not determine whether a specific address is contaminated
- Does not show groundwater plume extent — site boundary polygons are not plume boundaries
- Does not give health advice or health risk assessments
- Does not cover sites not on the EPA National Priorities List (state-only Superfund sites, brownfields, RCRA facilities)
- Does not show deleted or proposed NPL sites in primary results
- Is not affiliated with or endorsed by EPA

---

## Disclaimer Strategy

Proximity to a Superfund site boundary does not mean your property is contaminated. EPA site boundaries show the area designated for cleanup — not the full extent of contamination. Groundwater plumes can extend beyond site boundaries in the direction of groundwater flow. This tool shows publicly available EPA federal data. It is not a substitute for a professional environmental assessment.

**Displayed on:** results page (always), every `/site/{epa_id}` page, `/about`
**Not displayed on:** landing page (no results to disclaim yet)

---

## NIW Framing

**Domain:** Environmental Justice / Public Health / Federal Data Access
**Federal documentation:**
- EPA OIG Report 20-N-0231 (2020) — explicitly states EPA lacks a nationally consistent strategy for communicating health risks to communities near contaminated sites
- EPA Superfund Community Involvement Handbook (2016) — translation duty documented
- EJScreen removal February 5, 2025 — gap created by federal action

**Affected population:** 19.46 million within 1 mile (spatial audit 2026-03-22, strict count — active/final NPL with confirmed geometry only)
**Congressional reach:** 357 of 435 districts
**Rural reach:** 571 of 1,328 active sites (43%) are outside Census urban areas
**Demographic disproportionality:** EPA 2023 population report confirms near-site population is more minority, lower-income, and more linguistically isolated than national average

**Technical novelty:** True MultiPolygon boundary matching (not circular buffers) + human exposure control status join + distance-to-boundary computation. The verification work alone (cross-border population counting, 100+ polygon part handling, state-by-state TIGER block joins) demonstrates non-trivial spatial engineering that casual developers cannot replicate.

**Portfolio coherence with Tap Truth:** Both tools translate locked EPA environmental health data into plain-English address-level lookups for underserved communities. Together they form a coherent "EPA environmental data access" NIW theme.

---

## Comparison to Existing Tools

| Tool | What it does | Why it falls short |
|---|---|---|
| EPA "Search for Superfund Sites Where You Live" | Map and text search for NPL sites | No address input → distance output. No plain-language exposure status. Map interface only. |
| EPA SEMS (Superfund Enterprise Management System) | Full site data for regulators | Built for EPA staff and remediation professionals, not consumers |
| EPA Cleanups in My Community | Map of cleanup sites | No address-to-distance lookup. No exposure status. Multiple cleanup types mixed. |
| EJScreen | Environmental justice screening tool | **Removed February 5, 2025.** Was the main proximity tool. Now gone. |
| EPA MyEnvironment | Maps various environmental data | Broad multi-topic tool. No plain-language Superfund proximity answer. |
| Scorecard.org | Pollution data by ZIP | ZIP-level only, not address. Older data. Not Superfund-specific. |

Site Plain is the only tool that: takes a home address → finds actual NPL site polygons within miles → returns distance-to-boundary + exposure status in plain English.

---

## Metrics and Analytics — From Day 1

Lesson from Tap Truth: no usage data was captured. Site Plain ships with analytics on day 1.

### Server-side (no third-party dependency)

`lookup_audit` table records every lookup:
- Timestamp
- State of queried address
- Number of sites returned
- Distance to nearest site (if any)
- Geocoder used
- Whether lookup resolved successfully

This gives: daily lookup counts, state-level distribution, % of lookups finding a site, % finding a site within 1 mile — all without exposing user addresses.

### Google Search Console

- Submit sitemap on first deploy
- Monitor: impressions, clicks, top queries, indexed page count
- Target: impressions for "[city] Superfund site", "Superfund near me", site-specific queries

### Google Analytics (optional, Phase 2)

Add GA4 with IP anonymization if Google Search Console alone is insufficient. Do not add for Phase 1 to keep the tool dependency-free.

### Feedback Form

Embedded on results page and each `/site/{epa_id}` page:
- "Was this result helpful?" — Yes / No
- Optional: "What were you looking for?"
- Submit → stored in `feedback` database table (no third-party service, no email integration in Phase 1)

---

## Build Plan

### Phase 1 — Complete v1 (everything with available data)

**Goal:** Live on Railway with address lookup, results page, per-site SEO pages, state index pages, sitemap, feedback, and analytics. No known features deferred that have data available today.

1. **Data pipeline**
   - Convert NPL boundaries GDB → GeoJSON (Python script, one-time manual step)
   - Load `npl_site_boundaries` via PostGIS at startup
   - Load `npl_human_exposure` (always refreshed on startup)
   - Build `site_seo_page_cache` and `state_page_cache` at load time

2. **Core lookup**
   - Census Geocoder → Nominatim fallback
   - PostGIS `ST_DWithin` + `ST_Distance` to site boundary polygons
   - Human exposure status join + exposure pathway description
   - Results page — flat list ordered by distance

3. **SEO static pages**
   - `/site/{epa_id}` — one page per active NPL site (1,300+)
   - `/state/{code}` — one page per state (up to 49) — site count + full site list with exposure status
   - `/sitemap.xml` — precomputed at startup, includes all `/site/` and `/state/` pages
   - Title/meta tags on all pages

4. **Analytics + feedback**
   - `lookup_audit` table — every lookup logged
   - `feedback` table — results page + site pages
   - `robots.txt` allowing all crawlers

5. **Deploy to Railway**
   - `site-plain` app service + `postgres-postgis` service (already provisioned)
   - Submit sitemap to Google Search Console immediately on first deploy

### Phase 2 — OU sub-boundaries (complexity gate, not data gap)

- Load EPA NPL OU (operable unit) sub-boundaries (data exists — separate GDB from catalog.data.gov)
- Overlay OU polygons on results page: show which specific operable unit is nearest to the address
- Deferred because it adds overlay complexity to the results page, not because data is missing

### Phase 3 — Contamination type + PFAS (data gap)

- Contamination type: no field in NPL boundary GDB — source from SEMS/CERCLIS when available
- PFAS integration: EPA UCMR 5 data — evaluate as separate tool or Site Plain tab after Phase 1 is live

---

## Lessons from Tap Truth — Engineering Rules for Site Plain

These are problems that burned development time on Tap Truth. Do not repeat them.

| Rule | Why |
|---|---|
| Enable PostGIS on Railway PostgreSQL BEFORE writing any code | Confirm the Railway Postgres instance has PostGIS available. If not, the entire architecture fails. Check this on day 1. |
| Use staging tables for data refresh | Tap Truth deletes live data before confirming new download succeeded. If download fails mid-way, the table is empty and the app is broken. Site Plain must load into `_staging` tables, verify row counts, then swap atomically. |
| Never use `th:field` and `th:value` together on hidden inputs | Tap Truth bug: `th:field` ignores `th:value` and binds to empty form object. Use `name` attribute on hidden inputs. |
| Test geocoder on rural addresses before launch | "RR 2 Box 45" fails Census geocoder. Confirm Nominatim fallback works and returns graceful unresolved rather than wrong lat/lng. |
| Build `indexesPresent()` check in DataBootstrapService | Tap Truth bug: if raw data loaded but index rebuild crashed, subsequent restarts skipped everything. Check indexes separately from raw data. |
| `null` casts in PostgreSQL need explicit typing | Tap Truth bug: `cast(null as numeric)` — untyped null rejected by PostgreSQL, silently accepted by H2. Always cast nulls explicitly. |
| H2 in dev, PostgreSQL in prod — test spatial queries against PostgreSQL specifically | H2 does not support PostGIS. Spatial queries must be tested against a real PostgreSQL instance. Use Railway dev environment or local Docker. |
| SEO static pages from day 1, not Phase 2 | Tap Truth had 1 page indexed for weeks. Ship `/site/{epa_id}` and `/sitemap.xml` with Phase 1. |
| Do not use letter grades | Removed from Tap Truth after launch. Start without them. Show data, let user decide. |
| Sitemap submitted to Google Search Console immediately on first deploy | Do not wait. Submit on the same day the site goes live. |
| Two Railway Postgres services is a mistake | Tap Truth accidentally created a second service. One Postgres, one app. |
| `REFRESH_ON_STARTUP=false` after first load | Keep restarts instant. Data loads once. Refresh manually or on schedule. |

---

## Railway Operations

### Environment Variables

| Variable | Value / Note |
|---|---|
| `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` | GitHub release asset URL for NPL boundaries GDB/GeoJSON |
| `SITEPLAIN_DATA_HUMAN_EXPOSURE_URL` | `https://www3.epa.gov/semsjson/Human_Exposure_Site_List.json` |
| `SITEPLAIN_DATA_BOOTSTRAP_ENABLED` | `true` on first deploy, then review |
| `SITEPLAIN_DATA_REFRESH_ON_STARTUP` | `false` after first load |
| `SITEPLAIN_DATA_MAX_AGE_DAYS` | `90` (NPL data changes slowly — quarterly refresh is sufficient) |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `NIXPACKS_JDK_VERSION` | `21` |

### Deploying

```bash
cd /Users/manojbade/workspace/site-plain
mvn clean package -DskipTests
railway up
```

Watch logs for `"NPL boundary load complete"` and `"SEO cache built: X sites"` on first deploy.

### Cost estimate

Similar to Tap Truth — ~$5–15/month in steady state on Railway. NPL data is ~18MB compressed, significantly smaller than Tap Truth's EPA ECHO ZIP (~400MB). Startup time should be faster.

---

## Pre-Build Verification

All open questions resolved — see `_docs/pre-build-verification.md`.

---

*Full research backing in `/Users/manojbade/workspace/Info/superfund/`*
*Population audit: `/Users/manojbade/workspace/Info/superfund/superfund-population-audit.md`*
*Researcher outreach: `/Users/manojbade/workspace/Info/superfund/superfund-researcher-outreach.md`*
