# Site Plain — Implementation Doc

This document is based on the spec at `_docs/spec.md` and the pre-build verification at `_docs/pre-build-verification.md`. Every open question from the spec has been resolved here. Where the spec is silent, this doc makes a concrete v1 decision.

**Pre-build answers (verified 2026-03-22):**
- NPL boundaries: GDB only, layer `SITE_BOUNDARIES_SF`, 2,115 features, EPSG:4326. Must pre-convert to GeoJSON using Python/pyogrio before Java can load.
- Human exposure JSON: join key is `epaid` (lowercase) — same 12-char format as `EPA_ID` in GDB. Normalize both to uppercase on load.
- **No contamination type field in GDB.** `SITE_FEATURE_DESCRIPTION` is free text and inconsistent. Tool shows distance + exposure status only. No `contamination_type` column in schema.
- PostGIS geography ST_DWithin verified on Railway (2026-03-23). PostGIS 3.4 confirmed on `postgres-postgis` service.
- Railway project `site-plain` exists (created 2026-03-23). Service: `postgres-postgis` (postgis/postgis:16-3.4). Internal host: `postgres-postgis.railway.internal:5432`. Public proxy: `centerbeam.proxy.rlwy.net:54728`.

---

## 1. Project Structure

Root package: `com.siteplain`

```text
src/main/java/com/siteplain/
  SitePlainApplication.java
  config/
    WebConfig
    DataSourceConfig
    HttpClientConfig
    ThymeleafConfig
  web/
    controller/
      HomeController
      LookupController
      SitePageController
      StatePageController
      AboutController
      SitemapController
    form/
      AddressLookupForm
    advice/
      GlobalExceptionHandler
  service/
    GeocodingService
    NplLookupService
    SeoPageService
    DataBootstrapService
    DataRefreshPolicyService
    AuditService
    FeedbackService
    SitemapService
  data/
    loader/
      NplBoundaryLoader
      HumanExposureLoader
      SeoPageCacheBuilder
      StatePageCacheBuilder
    repository/
      NplBoundaryRepository
      HumanExposureRepository
      SeoPageRepository
      LookupAuditRepository
      FeedbackRepository
  domain/
    model/
      NplSite
      HumanExposureRecord
      GeocodedAddress
      NplLookupResult
      SeoPageData
    view/
      HomePageViewModel
      ResultsViewModel
      SitePageViewModel
      StatePageViewModel
      AboutPageViewModel
  support/
    ExposureStatusMapper
    DistanceFormatter
```

```text
src/main/resources/
  application.properties
  application-dev.properties
  application-prod.properties
  db/migration/
    V1__core_schema.sql
    V2__state_page_cache_schema.sql
    V3__lookup_audit_schema.sql
    V4__feedback_schema.sql
  templates/
    index.html
    results.html
    site.html
    state.html
    about.html
    error/
      unresolved.html
      not-found.html
    fragments/
      layout.html
      header.html
      footer.html
      disclaimer.html
      feedback-form.html
      exposure-badge.html
  static/
    css/
      site-plain.css
    robots.txt
```

---

## 2. Maven Dependencies (pom.xml)

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.2.4</version>
</parent>

<dependencies>
  <!-- Web + Thymeleaf -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
  </dependency>

  <!-- JDBC + PostgreSQL -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
  </dependency>
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
  </dependency>

  <!-- Flyway (schema migrations) -->
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
  </dependency>

  <!-- GeoJSON parsing for NPL boundary loading -->
  <dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
  </dependency>

  <!-- HTTP client for geocoding and human exposure JSON -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
  </dependency>

  <!-- Testing -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

No GeoTools or spatial Java library. GDB → GeoJSON conversion is handled by the Python pre-conversion script (see Section 4). Java only reads GeoJSON — straightforward coordinate parsing.

---

## 3. Database Schema (Flyway Migrations)

### V1__core_schema.sql

```sql
CREATE EXTENSION IF NOT EXISTS postgis;

-- NPL site boundary polygons (source: EPA NPL Boundaries GDB, layer SITE_BOUNDARIES_SF)
-- Input GeoJSON is pre-deduplicated: one polygon per EPA_ID (see scripts/convert_npl_to_geojson.py)
CREATE TABLE npl_site_boundaries (
  epa_id              varchar(20) PRIMARY KEY,
  site_name           varchar(255) NOT NULL,
  state_code          varchar(2),
  npl_status_code     varchar(10),              -- 'F' = final NPL, filter on this
  epa_url             text,                     -- URL_ALIAS_TXT from GDB (e.g. https://www.epa.gov/superfund/durham), nullable
  -- npl_listing_date omitted: no source field exists in NPL boundary GDB. Phase 2 may add from SEMS.
  geom                geometry(MultiPolygon, 4326) NOT NULL,
  loaded_at           timestamp NOT NULL DEFAULT now()
);

CREATE INDEX idx_npl_boundaries_geom ON npl_site_boundaries USING GIST(geom);
CREATE INDEX idx_npl_boundaries_state ON npl_site_boundaries(state_code);
CREATE INDEX idx_npl_boundaries_status ON npl_site_boundaries(npl_status_code);

-- Staging table for safe data refresh
CREATE TABLE npl_site_boundaries_staging (LIKE npl_site_boundaries INCLUDING ALL);

-- Human exposure status per site (source: EPA Human Exposure JSON feed)
-- Join key: normalize both sides to uppercase. epaid in JSON == EPA_ID in GDB.
CREATE TABLE npl_human_exposure (
  epa_id                  varchar(20) PRIMARY KEY REFERENCES npl_site_boundaries(epa_id),
  humexposurestscode      varchar(10),          -- HENC, HEUC, HEPR, HHPA, HEID
  humanexposurepathdesc   text,                 -- free text, exposure pathway description
  npl_status              varchar(5),           -- F, D, P
  site_name               varchar(255),
  loaded_at               timestamp NOT NULL DEFAULT now()
);

-- Staging table for safe data refresh
CREATE TABLE npl_human_exposure_staging (LIKE npl_human_exposure INCLUDING ALL);

-- SEO page cache — precomputed at load time, served at request time without DB join
CREATE TABLE site_seo_page_cache (
  epa_id                  varchar(20) PRIMARY KEY REFERENCES npl_site_boundaries(epa_id),
  site_name               varchar(255) NOT NULL,
  state_code              varchar(2),
  exposure_status_code    varchar(10),
  exposure_status_label   varchar(200),
  exposure_pathway_desc   text,
  epa_url                 text,                 -- copied from npl_site_boundaries.epa_url
  -- contamination_summary: no data source — not in schema
  -- npl_listing_date: no data source — not in schema
  computed_at             timestamp NOT NULL DEFAULT now()
);
```

### V2__state_page_cache_schema.sql

```sql
CREATE TABLE state_page_cache (
  state_code    varchar(2) PRIMARY KEY,
  state_name    varchar(100) NOT NULL,
  site_count    integer NOT NULL,
  computed_at   timestamp NOT NULL DEFAULT now()
);
-- Site list per state rendered via live join on site_seo_page_cache at request time
-- (max ~300 sites per state; join at render is fine — no per-site denormalization needed)
```

### V3__lookup_audit_schema.sql

```sql
CREATE TABLE lookup_audit (
  id              bigserial PRIMARY KEY,
  lookup_at       timestamp NOT NULL DEFAULT now(),
  state_code      varchar(2),
  result_count    integer,
  nearest_miles   numeric(8,3),
  geocoder_used   varchar(20),                  -- 'census' or 'nominatim'
  resolved        boolean NOT NULL
);

CREATE INDEX idx_lookup_audit_at ON lookup_audit(lookup_at);
CREATE INDEX idx_lookup_audit_state ON lookup_audit(state_code);
```

### V4__feedback_schema.sql

```sql
CREATE TABLE feedback (
  id          bigserial PRIMARY KEY,
  submitted_at timestamp NOT NULL DEFAULT now(),
  page_type   varchar(20),                      -- 'results' or 'site'
  epa_id      varchar(20),                      -- NULL for results page feedback
  helpful     boolean,
  comments    text
);
```

---

## 4. GDB → GeoJSON Pre-Conversion (Python Script)

Java cannot read GDB natively. Before coding starts, run this Python script to produce a GeoJSON file, then upload to a GitHub release as a static asset.

**Script:** `scripts/convert_npl_to_geojson.py`

```python
#!/usr/bin/env python3
"""
Converts NPL_Boundaries.gdb (layer SITE_BOUNDARIES_SF) to GeoJSON.
Outputs: npl_site_boundaries.geojson

Requirements: pip install geopandas pyogrio
"""
import geopandas as gpd
import json
import sys

GDB_PATH = "/Users/manojbade/workspace/Info/superfund/superfund_audit_data/NPL_Boundaries/NPL_Boundaries.gdb"
LAYER = "SITE_BOUNDARIES_SF"
OUTPUT = "npl_site_boundaries.geojson"

print(f"Reading layer '{LAYER}' from GDB...")
gdf = gpd.read_file(GDB_PATH, layer=LAYER, engine="pyogrio")

print(f"Features read: {len(gdf)}")
print(f"CRS: {gdf.crs}")
print(f"Geometry types: {gdf.geom_type.unique()}")

# Already EPSG:4326 — no reprojection needed
# Keep only the fields needed for Site Plain
keep_cols = [
    "EPA_ID", "SITE_NAME", "STATE_CODE", "NPL_STATUS_CODE",
    "SITE_FEATURE_TYPE", "SITE_FEATURE_DESCRIPTION",
    "URL_ALIAS_TXT",  # EPA's friendly site URL (e.g. https://www.epa.gov/superfund/durham), nullable
    "geometry"
]
gdf = gdf[[c for c in keep_cols if c in gdf.columns]]

# Filter to Final NPL only
final_only = gdf[gdf["NPL_STATUS_CODE"] == "F"].copy()
print(f"Final NPL features (NPL_STATUS_CODE='F'): {len(final_only)}")

# Deduplicate: multiple boundary types exist per site. Pick one per EPA_ID.
# Priority: most conservative enclosing boundary first.
# A consumer asking "how close am I?" should get the full designated site area,
# not a sub-boundary that would make the site appear farther away.
FEATURE_TYPE_PRIORITY = [
    "Comprehensive Site Area",        # full designated site area — preferred
    "Total Site Polygon/OU Aggregation",
    "Site Boundary",
    "Current Ground Boundary",
    "OU Boundary Aggregation",
    "Extent of Contamination",
    "Contamination Boundary (Groundwater)",
    "Contamination Boundary",
    "Other",
]

def feature_type_rank(feature_type):
    try:
        return FEATURE_TYPE_PRIORITY.index(feature_type)
    except (ValueError, TypeError):
        return len(FEATURE_TYPE_PRIORITY)

final_only["_rank"] = final_only["SITE_FEATURE_TYPE"].apply(feature_type_rank)
final_only = final_only.sort_values(["EPA_ID", "_rank"])
deduplicated = final_only.drop_duplicates(subset=["EPA_ID"], keep="first")
deduplicated = deduplicated.drop(columns=["_rank"])

print(f"After deduplication: {len(deduplicated)} unique sites")
print("SITE_FEATURE_TYPE selected (value counts):")
print(deduplicated["SITE_FEATURE_TYPE"].value_counts().to_string())

deduplicated.to_file(OUTPUT, driver="GeoJSON")
print(f"Written to {OUTPUT}")

# Note on boundary methodology vs population audit:
# The population audit (superfund-population-audit.md) dissolved all polygons per EPA_ID
# into one geometry before counting nearby census blocks. This tool picks one polygon
# per EPA_ID by priority (most comprehensive boundary wins). The two approaches produce
# different geometries for multi-boundary sites — that is intentional. The audit needed
# a conservative union to avoid double-counting population. This tool needs one clean
# authoritative boundary for distance computation. "Comprehensive Site Area" is that boundary.
```

Run once:
```bash
cd /Users/manojbade/workspace/site-plain
python scripts/convert_npl_to_geojson.py
```

Upload `npl_site_boundaries.geojson` to GitHub releases for site-plain. Set the env var:
```
SITEPLAIN_DATA_NPL_BOUNDARIES_URL=https://github.com/[user]/site-plain/releases/download/data-v1/npl_site_boundaries.geojson
```

---

## 5. Data Loading — DataBootstrapService

```
startup
  ↓
SITEPLAIN_DATA_BOOTSTRAP_ENABLED == true?
  ↓ yes
-- Refresh policy (split by data source size):
--   NPL boundaries (~18MB GeoJSON): controlled by SITEPLAIN_DATA_REFRESH_ON_STARTUP
--     defaults false after first load — refresh manually by toggling to true + redeploy
--   Human exposure JSON (~500KB live feed): ALWAYS refreshed on startup
--     fast enough to refresh every deploy; keeps exposure status current

DataRefreshPolicyService.shouldRefreshBoundaries()
  → false: skip boundary download (default after first load)
  → true: run steps 1–5

DataBootstrapService always runs steps 6–11 (human exposure + SEO cache) on every startup.

Boundary refresh (when enabled):
      1. TRUNCATE npl_site_boundaries_staging  -- clear before load to avoid stale rows
      2. Download npl_site_boundaries.geojson from env var URL
      3. NplBoundaryLoader.loadIntoStaging(geojson)
         - input is pre-deduplicated (one feature per EPA_ID — deduplication done in Python script)
         - parse GeoJSON features
         - for each feature: insert into npl_site_boundaries_staging
         - normalize EPA_ID to uppercase
         - map URL_ALIAS_TXT → epa_url (store as-is, nullable)
         - cast geometry using PostGIS ST_GeomFromGeoJSON
      4. Verify staging row count > 0 and > (live count * 0.9)
      5. FK-safe atomic swap (single transaction):
            TRUNCATE site_seo_page_cache;           -- drop dependents first
            TRUNCATE npl_human_exposure;             -- drop dependents first
            TRUNCATE npl_site_boundaries;
            INSERT INTO npl_site_boundaries SELECT * FROM npl_site_boundaries_staging;
         Rebuild GIST index after swap.

Human exposure refresh (always runs):
      6. TRUNCATE npl_human_exposure_staging  -- clear before load
      7. Download Human_Exposure_Site_List.json from SITEPLAIN_DATA_HUMAN_EXPOSURE_URL
      8. HumanExposureLoader.loadIntoStaging(json)
         - parse data[] array
         - normalize epaid → epa_id (uppercase)
         - INSERT INTO npl_human_exposure_staging (only rows with matching epa_id in live npl_site_boundaries)
      9. Verify staging count > 1500
      10. TRUNCATE npl_human_exposure; INSERT INTO npl_human_exposure SELECT * FROM npl_human_exposure_staging;

SEO cache rebuild (always runs after human exposure):
      11. SeoPageCacheBuilder.rebuild()
          - TRUNCATE site_seo_page_cache
          - for each site in npl_site_boundaries WHERE npl_status_code='F':
            - join with npl_human_exposure (LEFT JOIN — missing exposure rows render as gray "not reported")
            - map humexposurestscode → label (ExposureStatusMapper)
            - copy epa_url from npl_site_boundaries
          - INSERT new rows
      12. StatePageCacheBuilder.rebuild()
          - TRUNCATE state_page_cache
          - SELECT state_code, count(*) FROM npl_site_boundaries WHERE npl_status_code='F' GROUP BY state_code
          - Map state_code → state_name (hardcoded map of 50 state codes)
          - INSERT one row per state
      13. SitemapService.rebuild() — regenerate in-memory sitemap XML
      14. Log: "Bootstrap complete. Sites: X, Exposure records: Y, SEO pages: Z, State pages: N"
```

### Insert pattern for GeoJSON geometry (Spring JDBC)

```java
// Use PostGIS ST_GeomFromGeoJSON + ST_SetSRID to guarantee correct SRID
String sql = """
    INSERT INTO npl_site_boundaries_staging
      (epa_id, site_name, state_code, npl_status_code, geom, loaded_at)
    VALUES
      (:epaId, :siteName, :stateCode, :nplStatusCode,
       ST_SetSRID(ST_GeomFromGeoJSON(:geomJson), 4326), now())
    ON CONFLICT (epa_id) DO UPDATE SET
      site_name = EXCLUDED.site_name,
      geom = EXCLUDED.geom,
      loaded_at = EXCLUDED.loaded_at
    """;
```

**Note:** `ON CONFLICT DO UPDATE` instead of TRUNCATE+INSERT-per-row — avoids partial loads if the process dies mid-load.

---

## 6. Core Lookup — NplLookupService

### Step 1: Geocode

```java
// GeocodingService — same pattern as Tap Truth
GeocodedAddress geocode(String address) {
    // Try Census Geocoder first
    // https://geocoding.geo.census.gov/geocoder/locations/onelineaddress
    //   ?address=...&benchmark=Public_AR_Current&format=json
    // If response has matchedAddress: return with geocoder='census'
    // If no match or HTTP error: try Nominatim
    // https://nominatim.openstreetmap.org/search?q=...&format=json&limit=1&countrycodes=us
    // If Nominatim returns result: return with geocoder='nominatim'
    // If both fail: return GeocodedAddress.unresolved()
}
```

### Step 2: PostGIS Lookup

```java
// NplLookupService.findSitesNear(double lat, double lng)
String sql = """
    SELECT
      s.epa_id,
      s.site_name,
      s.state_code,
      s.npl_status_code,
      s.epa_url,
      ST_Distance(
        s.geom::geography,
        ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
      ) AS distance_meters,
      h.humexposurestscode,
      h.humanexposurepathdesc
    FROM npl_site_boundaries s
    LEFT JOIN npl_human_exposure h ON h.epa_id = s.epa_id
    WHERE
      s.npl_status_code = 'F'
      AND ST_DWithin(
        s.geom::geography,
        ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
        4828.032   -- 3 miles in meters (3 * 1609.344)
      )
    ORDER BY distance_meters ASC
    """;
```

**Important:** `:lng` before `:lat` — ST_MakePoint is (longitude, latitude). Do not swap.

### effectiveEpaUrl() — Domain Helper

Add to `NplSite` domain model (also add equivalent to `SeoPageData`):

```java
// Returns the best available EPA URL for this site.
// Uses URL_ALIAS_TXT from the GDB when present; falls back to EPA site search by EPA_ID.
public String effectiveEpaUrl() {
    if (epaUrl != null && !epaUrl.isBlank()) {
        return epaUrl;
    }
    return "https://cumulis.epa.gov/supercpad/cursites/srchsites.cfm?search_string=" + epaId;
}
```

### Step 3: Enrich and Return

```java
NplLookupResult result = new NplLookupResult();
result.setAddress(geocodedAddress);
result.setSites(rows.stream()
    .map(row -> {
        NplSite site = mapRow(row);
        site.setDistanceMeters(row.getDouble("distance_meters"));
        site.setExposureStatusLabel(ExposureStatusMapper.label(row.getString("humexposurestscode")));
        site.setExposureStatusColor(ExposureStatusMapper.color(row.getString("humexposurestscode")));
        return site;
    })
    .collect(toList()));
result.setNearestMiles(result.getSites().isEmpty() ? null
    : result.getSites().get(0).getDistanceMeters() / 1609.344);
return result;
```

---

## 7. ExposureStatusMapper

```java
public class ExposureStatusMapper {

    public static String label(String code) {
        if (code == null) return "Exposure status not reported";
        return switch (code.toUpperCase()) {
            case "HENC" -> "Human exposure is NOT currently under control";
            case "HEUC" -> "Human exposure is under control";
            case "HEPR" -> "Human exposure is under control — protective remedies in place";
            case "HHPA" -> "Long-term health protection has been achieved";
            case "HEID" -> "Exposure status: insufficient data";
            default -> "Exposure status: " + code;
        };
    }

    public static String color(String code) {
        if (code == null) return "gray";
        return switch (code.toUpperCase()) {
            case "HENC" -> "red";
            case "HEUC", "HEPR" -> "yellow";
            case "HHPA" -> "green";
            default -> "gray";
        };
    }

    public static String badgeCssClass(String code) {
        return "badge-exposure-" + color(code);
    }
}
```

---

## 8. DistanceFormatter

```java
public class DistanceFormatter {

    public static String format(double meters) {
        double feet = meters * 3.28084;
        double miles = meters / 1609.344;

        if (feet < 528) {  // < 0.1 miles
            if (feet < 10) {
                return "Less than 10 feet (inside or immediately adjacent to site boundary)";
            }
            return String.format("%.0f feet (less than 0.1 miles)", feet);
        } else if (miles < 0.5) {
            return String.format("%.0f feet (%.1f miles)", feet, miles);
        } else {
            return String.format("%.1f miles", miles);
        }
    }

    // tier() intentionally omitted — results display is a flat list, no tier grouping
}
```

---

## 9. Controllers

### HomeController

```java
// GET /
// Returns index.html with HomePageViewModel
// HomePageViewModel contains:
//   - totalSiteCount (from npl_site_boundaries WHERE npl_status_code='F')
//   - totalPopulation = 19.46 million (hardcoded from audit)
//   - totalDistricts = 357 (hardcoded from audit)
```

### LookupController

```java
// POST /lookup  →  PRG pattern (Post-Redirect-Get)
// Accepts AddressLookupForm (address String, trimmed, max 200 chars)
//
// 1. Geocode address
// 2. Log to lookup_audit (always — even on failure)
// 3a. If unresolved: redirect to /results?error=unresolved
// 3b. If resolved: redirect to /results?lat={lat}&lng={lng}&address={encoded}
//
// GET /results
// Params: lat, lng, address (on success) OR error=unresolved (on failure)
// 1. If error=unresolved: return results.html with unresolved=true (renders error/unresolved fragment)
// 2. If lat+lng present: run PostGIS lookup, build ResultsViewModel, return results.html
//
// PRG prevents form resubmission on browser back/refresh.
```

### SitePageController

```java
// GET /site/{epaId}
// 1. Normalize epaId to uppercase
// 2. Query site_seo_page_cache WHERE epa_id = :normalizedEpaId
// 3. If not found: return 404 (no redirect — URLs are generated from DB so mismatches
//    should not happen in practice; just 404 cleanly)
// 4. Return site.html with SitePageViewModel
```

### SitemapController

```java
// GET /sitemap.xml
// Returns XML response (content type: application/xml)
//
// Precomputed at startup: SitemapService builds the full XML string once
// after DataBootstrapService completes, stores it in memory.
// SitemapController returns the cached string directly — no DB query per request.
// SitemapService.rebuild() is called at end of each bootstrap cycle.
//
// Includes:
//   - https://site-plain.com/
//   - https://site-plain.com/about
//   - https://site-plain.com/site/{epa_id} for every npl_status_code='F' site
//   - https://site-plain.com/state/{code} for every state with at least one active site
//
// friendlyurl from human exposure JSON: ignored. URL_ALIAS_TXT from GDB is the
// canonical EPA external link (stored as epa_url). friendlyurl is redundant.
```

### StatePageController

```java
// GET /state/{code}
// 1. Normalize code to uppercase (e.g. "ca" → "CA")
// 2. Query state_page_cache WHERE state_code = :code
// 3. If not found: 404
// 4. Query site_seo_page_cache WHERE state_code = :code ORDER BY site_name ASC
// 5. Return state.html with StatePageViewModel (stateName, siteCount, sites list)
```

### AboutController

```java
// GET /about
// Static page — no DB query
```

### FeedbackController

```java
// POST /feedback
// Accepts: application/x-www-form-urlencoded
// Parameters:
//   pageType  String — "results" or "site"
//   epaId     String (optional) — EPA_ID for site pages, null for results page
//   helpful   Boolean (optional) — true/false from button value
//   comments  String (optional) — free text, truncated to 500 chars silently
//
// Behavior:
//   1. Sanitize pageType/epaId to alphanumeric + underscore + hyphen only
//   2. Truncate comments to 500 chars
//   3. FeedbackService.save(...)
//   4. Redirect to /feedback/thanks (always — even on DB error)
//
// GET /feedback/thanks
//   @ResponseBody — returns plain "Thank you for your feedback." string
//   No template needed.
```

```java
// FeedbackService.save(String pageType, String epaId, Boolean helpful, String comments)
// - Applies truncation and sanitization
// - INSERT INTO feedback (submitted_at, page_type, epa_id, helpful, comments) VALUES (...)
// - Logs: log.info("Feedback: pageType={}, helpful={}", pageType, helpful)
// - Catches DataAccessException — logs WARN, does not rethrow
```

---

## 10. Templates

### index.html

- Single centered address input form (POST to `/lookup`)
- Tagline: "What's on the EPA priority list near your home?"
- Stats block: "19.4 million Americans live within 1 mile of an active Superfund site. 357 of 435 congressional districts contain at least one."
- No color on landing page. No alarm language.
- Source credit: "Data: EPA National Priorities List"

### results.html

- Header: address confirmed (from geocoder)
- If sites found:
  - Count: "X active Superfund site(s) found within 3 miles"
  - `th:each` loop over sites, sorted by distance_meters ASC
  - Flat list ordered by distance ascending — no tier grouping, no collapsing
  - Each site card (identical layout regardless of distance):
    - Site name (linked to `/site/{epaId}`)
    - Distance — `DistanceFormatter.format(distanceMeters)` (e.g. "340 feet", "0.4 miles")
    - Human exposure status — color-coded label + one plain-English sentence
    - If `humanexposurepathdesc` not null/empty: `<p>About this site's exposure pathways: [text]</p>`
    - External link: `th:href="${site.effectiveEpaUrl()}"` — text "View EPA site profile →"
- If no sites:
  - "No active EPA Superfund sites found within 3 miles of this address."
  - Do NOT say "you're safe" or "your area is clean"
  - Show national context: "The EPA National Priorities List includes 1,328 active sites nationally."
- Disclaimer block (always shown — `th:replace="fragments/disclaimer"`
- Feedback form fragment

### site.html (SEO static page)

- H1: site name
- State, EPA ID
- Exposure status — color-coded label + plain-language sentence
- If `exposurePathwayDesc` not null/empty: `<p>About this site's exposure pathways: [text]</p>`
- "Enter your address to see how close you are to this site" — address form (POST to `/lookup`)
- External link: `th:href="${page.effectiveEpaUrl()}"` — text "View EPA site profile →"
- Disclaimer block
- Title tag: `[Site Name] — EPA Superfund Site | Site Plain`
- Meta description: `[Site Name] is an active EPA Superfund site in [State]. Human exposure status: [label]. Enter your address to see your distance from this site.`
- `<link rel="canonical" href="https://site-plain.com/site/{epaId}" />`

### fragments/disclaimer.html

```html
<div th:fragment="disclaimer" class="disclaimer-block">
  <p><strong>Data limitations:</strong> Proximity to a Superfund site boundary does not mean
  your property is contaminated. EPA site boundaries show the area designated for cleanup —
  not the full extent of contamination. Groundwater plumes can extend beyond site boundaries.
  This tool shows publicly available EPA federal data. It is not affiliated with or endorsed
  by EPA, and is not a substitute for a professional environmental assessment.</p>
</div>
```

### state.html (State Index Page)

- H1: "[State Name] EPA Superfund Sites"
- Count: "[N] active sites on the EPA National Priorities List"
- Flat list of all sites in state (from site_seo_page_cache WHERE state_code = :code, ORDER BY site_name ASC):
  - Site name linked to `/site/{epa_id}`
  - Exposure status badge (color) + label
- "Enter your address to find sites near you" — address input (POST to `/lookup`)
- Title tag: `EPA Superfund Sites in [State Name] ([N] active sites) | Site Plain`
- Meta description: `[N] active EPA Superfund sites in [State]. Enter your address to see how close you are to any of them.`
- Canonical: `/state/{CODE}` (uppercase)
- robots: `index, follow`

### fragments/feedback-form.html

```html
<form th:fragment="feedback-form" method="post" action="/feedback">
  <p>Was this information helpful?</p>
  <input type="hidden" name="pageType" th:value="${pageType}" />
  <input type="hidden" name="epaId" th:value="${epaId}" />
  <button type="submit" name="helpful" value="true">Yes</button>
  <button type="submit" name="helpful" value="false">No</button>
  <textarea name="comments" placeholder="What were you looking for? (optional)" maxlength="500"></textarea>
</form>
```

**Note:** Do not use `th:field` on hidden inputs — use `name` and `th:value` separately. (Tap Truth lesson: `th:field` ignores `th:value`.)

---

## 11. Application Properties

### application.properties

```properties
spring.application.name=site-plain
spring.profiles.active=dev
server.port=8080

# Flyway
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration

# Data bootstrap
siteplain.data.bootstrapEnabled=true
siteplain.data.refreshOnStartup=false
siteplain.data.maxAgeDays=90
siteplain.data.nplBoundariesUrl=${SITEPLAIN_DATA_NPL_BOUNDARIES_URL}
siteplain.data.humanExposureUrl=${SITEPLAIN_DATA_HUMAN_EXPOSURE_URL:https://www3.epa.gov/semsjson/Human_Exposure_Site_List.json}
```

### application-dev.properties

```properties
# Dev: set DEV_DATABASE_URL in your local environment — do not hardcode here.
# Railway postgres-postgis public proxy: jdbc:postgresql://centerbeam.proxy.rlwy.net:54728/railway
# Example local export:
#   export DEV_DATABASE_URL=jdbc:postgresql://centerbeam.proxy.rlwy.net:54728/railway
#   export DEV_DB_USERNAME=postgres
#   export DEV_DB_PASSWORD=<from Railway postgres-postgis variables>
spring.datasource.url=${DEV_DATABASE_URL}
spring.datasource.username=${DEV_DB_USERNAME}
spring.datasource.password=${DEV_DB_PASSWORD}
# H2 is NOT used. All spatial queries require real PostgreSQL with PostGIS.
```

### application-prod.properties

```properties
# Railway injects DATABASE_URL as postgresql://user:pass@host:port/db (not JDBC format).
# Use explicit JDBC vars instead — set these in Railway app service variables.
# SPRING_DATASOURCE_URL=jdbc:postgresql://postgres-postgis.railway.internal:5432/railway
# SPRING_DATASOURCE_USERNAME=postgres
# SPRING_DATASOURCE_PASSWORD=<from postgres-postgis service>
spring.datasource.url=${SPRING_DATASOURCE_URL}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}
siteplain.data.refreshOnStartup=false
```

---

## 12. Railway Environment Variables

| Variable | Value |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres-postgis.railway.internal:5432/railway` — explicit JDBC format. Railway's `DATABASE_URL` is `postgresql://...` (not JDBC) and Spring Boot does not auto-convert it. |
| `SPRING_DATASOURCE_USERNAME` | `postgres` |
| `SPRING_DATASOURCE_PASSWORD` | Reference from `postgres-postgis` service: `${{postgres-postgis.POSTGRES_PASSWORD}}` |
| `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` | GitHub release URL for `npl_site_boundaries.geojson` |
| `SITEPLAIN_DATA_HUMAN_EXPOSURE_URL` | `https://www3.epa.gov/semsjson/Human_Exposure_Site_List.json` |
| `SITEPLAIN_DATA_BOOTSTRAP_ENABLED` | `true` on first deploy |
| `SITEPLAIN_DATA_REFRESH_ON_STARTUP` | `false` after data is loaded |
| `SITEPLAIN_DATA_MAX_AGE_DAYS` | `90` |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `NIXPACKS_JDK_VERSION` | `21` |

---

## 13. AuditService

Every lookup writes one row to `lookup_audit`:

```java
// AuditService.logLookup(GeocodedAddress address, NplLookupResult result)
String sql = """
    INSERT INTO lookup_audit (lookup_at, state_code, result_count, nearest_miles, geocoder_used, resolved)
    VALUES (now(), :stateCode, :resultCount, :nearestMiles, :geocoderUsed, :resolved)
    """;
```

Called from `LookupController` after every lookup, regardless of result. Non-blocking — log failure is caught and swallowed, never propagates to user response.

---

## 14. robots.txt

```
User-agent: *
Allow: /

Sitemap: https://site-plain.com/sitemap.xml
```

Place in `src/main/resources/static/robots.txt`. Spring Boot serves static files from `/static/` automatically.

---

## 15. Build + Deploy Sequence

### Railway setup (already done — 2026-03-23)

Railway project `site-plain` exists. Service `postgres-postgis` (postgis/postgis:16-3.4) is live with PostGIS 3.4 confirmed. Internal host: `postgres-postgis.railway.internal:5432`. Public proxy: `centerbeam.proxy.rlwy.net:54728`. No setup steps needed before first deploy.

### First deploy

1. Run `scripts/convert_npl_to_geojson.py` — produces `npl_site_boundaries.geojson`
2. Upload `npl_site_boundaries.geojson` to GitHub releases
3. Set `SITEPLAIN_DATA_NPL_BOUNDARIES_URL` env var in Railway to the release URL
4. Set `SITEPLAIN_DATA_BOOTSTRAP_ENABLED=true`
5. Build: `mvn clean package -DskipTests`
6. Deploy: `railway up`
7. Watch logs for:
   - `"NPL boundaries loaded into staging: X rows"`
   - `"Staging count verified. Swapping into live table."`
   - `"Human exposure loaded: Y rows"`
   - `"SEO cache built: Z sites"`
   - `"Bootstrap complete."`
8. After successful first load: set `SITEPLAIN_DATA_REFRESH_ON_STARTUP=false`

### Post-deploy (same day)

1. Submit sitemap to Google Search Console: `https://site-plain.com/sitemap.xml`
2. Test a handful of address lookups manually
3. Test 3-4 `/site/{epaId}` pages manually
4. Verify `lookup_audit` table has rows

---

## 16. Codex Instructions

This section is the precise handoff. Implement in this order:

1. **Maven project + directory structure** (Section 1 + Section 2)
   - Create the full directory tree
   - Set up pom.xml with all dependencies
   - Create empty placeholder classes for all packages

2. **Flyway migrations** (Section 3)
   - V1: core schema including PostGIS extension, staging tables, indexes
   - V2: state_page_cache
   - V3: lookup_audit
   - V4: feedback

3. **DataBootstrapService + loaders** (Section 5)
   - NplBoundaryLoader: reads GeoJSON, inserts into staging via Spring JDBC + ST_GeomFromGeoJSON
   - HumanExposureLoader: fetches JSON from URL, normalizes epaid→EPA_ID uppercase, inserts into staging
   - SeoPageCacheBuilder: joins both tables, maps exposure codes, builds cache
   - StatePageCacheBuilder: counts active sites per state, maps state_code → state_name, inserts into state_page_cache
   - DataBootstrapService: orchestrates the staging→live swap with verification

4. **GeocodingService** (Section 6, Step 1)
   - Census Geocoder primary
   - Nominatim fallback
   - GeocodedAddress.unresolved() for both-fail case

5. **NplLookupService** (Section 6, Step 2 + 3)
   - PostGIS ST_DWithin + ST_Distance query
   - ExposureStatusMapper + DistanceFormatter

6. **Controllers** (Section 9)
   - HomeController, LookupController, SitePageController, StatePageController, SitemapController, AboutController

7. **Templates** (Section 10)
   - index.html, results.html, site.html, state.html, about.html, fragments

8. **AuditService** (Section 13)
   - Called from LookupController on every lookup

8a. **FeedbackController + FeedbackService** (Section 9, FeedbackController)
   - POST /feedback → save row → redirect /feedback/thanks
   - GET /feedback/thanks → plain text @ResponseBody

9. **robots.txt** (Section 14)

10. **Application properties** (Section 11)

**Do not add H2 dependency** — all dev testing runs against local PostgreSQL with PostGIS. H2 cannot test spatial queries.

**Do not add letter grades** — exposure status is shown as a color badge + label only.

**Do not add third-party analytics** (no GA4, no Mixpanel) — lookup_audit table is the only analytics in Phase 1.

---

*Last updated: 2026-03-22*
*Status: Ready for Codex — pending Railway login and PostGIS confirmation*
