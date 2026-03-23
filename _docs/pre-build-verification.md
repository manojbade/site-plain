# site-plain — Pre-Build Verification
Date: 2026-03-22

---

## Q1 — PostGIS on Railway

**Railway project status:** ✅ Project `site-plain` created (2026-03-23). Project ID: `bcd68885-1840-4be3-9c6c-75ced28d9cde`

**Database service:** `postgres-postgis` — Docker image `postgis/postgis:16-3.4` (NOT Railway's default Postgres — that image does not have PostGIS compiled in)

**PostGIS version confirmed:** `3.4 USE_GEOS=1 USE_PROJ=1 USE_STATS=1`
- GEOS: 3.9.0-CAPI-1.16.2
- PostGIS lib: 3.4.3
- Loaded into `railway` database at container init (confirmed in startup logs)

**Connection:**
- Internal (Railway service-to-service): `postgresql://postgres:***@postgres-postgis.railway.internal:5432/railway`
- Public (TCP proxy): `postgresql://postgres:***@centerbeam.proxy.rlwy.net:54728/railway`

**Important:** Railway's native Postgres service (`ghcr.io/railwayapp-templates/postgres-ssl`) does NOT have PostGIS. Always use the `postgres-postgis` service (Docker image: `postgis/postgis:16-3.4`). The app service must reference `$DATABASE_URL` from the `postgres-postgis` service, not from any native Postgres service.

---

## Q2 — NPL Boundary Data Format

**Local data location:** `/Users/manojbade/workspace/Info/superfund/superfund_audit_data/NPL_Boundaries/NPL_Boundaries.gdb`

**Format:** GeoDatabase (GDB) only — no GeoJSON or Shapefile present in that directory. However, geopandas + pyogrio can read GDB directly, so conversion is straightforward when needed.

**Layer inspected:** `SITE_BOUNDARIES_SF` (the correct layer for site boundaries)

Other layers in the GDB (not needed for proximity lookup):
- `SITE_FEATURE_LINES_SF` (MultiLineString Z)
- `IC_BOUNDARIES_SF` (MultiPolygon)
- `OU_BOUNDARIES_SF` (MultiPolygon)
- `SITE_FEATURE_POLYS_SF` (MultiPolygon)
- `SITE_FEATURE_POINTS_SF` (Point)

**Geometry type:** `MultiPolygon` — confirmed.

**CRS:** EPSG:4326 — already in WGS84, no reprojection needed for PostGIS geography operations.

**Total features:** 2,115

**Site identifier field:** `EPA_ID`
- Example value: `CTD001452093`
- Format: 2-letter state code + 1-letter program code + 9-digit CERCLIS ID

**NPL status field:** `NPL_STATUS_CODE`
- Example value: `'F'` (Final NPL listing)

---

## Q3 — Human Exposure JSON Field Names

**URL:** `https://www3.epa.gov/semsjson/Human_Exposure_Site_List.json`

**Response structure:** `{ "success": true, "data": [...], "meta": {...} }`

**Total records in `data` array:** 1,906

**All field names (from first 3 records):**
```
humanexposurepathdesc
city
nplstatus
saaflag
county
fedfacilityflag
zipcode
epaid
regionid
sitename
eibaselinesiteInd
siteid
humexposurestscode
state
state_code
friendlyurl
```

**Site identifier field:** `epaid` (all lowercase)
- Example values: `WAN001003081`, `WAN001002907`, `IDN001002859`

**Join compatibility with NPL boundary data:**
- NPL boundary field: `EPA_ID` (uppercase)
- Human exposure field: `epaid` (lowercase)
- **Format is identical** — same 12-character structure (2-letter state + 1-letter program + 9-digit ID)
- Join works on value, just handle case difference in code (e.g., `epaid.upper()` == `EPA_ID`)

**Key fields for the app:**
- `humexposurestscode` — human exposure status code (e.g., `HEUC` = Human Exposure Under Control, `HENC` = Human Exposure Not Under Control)
- `humanexposurepathdesc` — full narrative description of exposure pathways
- `nplstatus` — NPL listing status (`F` = Final, `N` = Non-NPL/proposed)

---

## Q4 — Contamination Type Field

**Layer:** `SITE_BOUNDARIES_SF`

**Complete field list with sample values:**

| Field | Sample Value |
|-------|-------------|
| `REGION_CODE` | `1` |
| `EPA_PROGRAM` | `'Superfund Remedial'` |
| `EPA_ID` | `'CTD001452093'` |
| `SITE_NAME` | `'DURHAM MEADOWS'` |
| `SITE_FEATURE_CLASS` | `5` (integer code) |
| `SITE_FEATURE_TYPE` | `'Extent of Contamination'` |
| `SITE_FEATURE_NAME` | `'Approximate Durham Meadows Site Boundary'` |
| `SITE_FEATURE_DESCRIPTION` | Long text description of site |
| `LAST_CHANGE_DATE` | `2024-06-30 UTC` |
| `ORIGINAL_CREATION_DATE` | `2016-12-31 UTC` |
| `SITE_FEATURE_SOURCE` | `'U.S. EPA Region 1'` |
| `FEATURE_INFO_URL` | `'https://semspub.epa.gov/src/document/01/100018870'` |
| `FEATURE_INFO_URL_DESC` | `'5th Five Year Review'` |
| `PROJECTION` | `'Albers Equal Area'` |
| `GIS_AREA` | `82.42` |
| `GIS_AREA_UNITS` | `'Acres'` |
| `SF_GEOSPATIAL_DATA_DISCLAIMER` | Boilerplate legal disclaimer text |
| `URL_ALIAS_TXT` | `'https://www.epa.gov/superfund/durham'` |
| `NPL_STATUS_CODE` | `'F'` |
| `FEDERAL_FACILITY_DETER_CODE` | `'N'` |
| `STREET_ADDR_TXT` | `'MAIN ST'` |
| `ADDR_COMMENT` | Supplemental address notes (nullable) |
| `CITY_NAME` | `'DURHAM'` |
| `COUNTY` | `'MIDDLESEX'` |
| `STATE_CODE` | `'CT'` |
| `ZIP_CODE` | `'06422'` |
| `SITE_CONTACT_NAME` | `'Lisa Danek Burke'` |
| `PRIMARY_TELEPHONE_NUM` | `'(617) 918-1206'` |
| `SITE_CONTACT_EMAIL` | `'DanekBurke.Lisa@epa.gov'` |
| `Shape_Length` | `0.029649` |
| `Shape_Area` | `3.596e-05` |
| `geometry` | MultiPolygon |

**Contamination type — finding:**

There is NO dedicated chemical name, contaminant type, or waste type field in this layer. The closest fields are:

- `SITE_FEATURE_TYPE` — describes the boundary classification, not the contaminant. Values include:
  - `Comprehensive Site Area` (886 features)
  - `Current Ground Boundary` (555)
  - `Total Site Polygon/OU Aggregation` (187)
  - `Site Boundary` (172)
  - `Extent of Contamination` (155)
  - `OU Boundary Aggregation` (68)
  - `Other` (53)
  - `Contamination Boundary` (7)
  - `Contamination Boundary (Groundwater)` (5)

- `SITE_FEATURE_DESCRIPTION` — free text, sometimes mentions specific contaminants but is inconsistent and not machine-parseable as a category field.

**Conclusion:** Contamination type (chemical category, waste type) is NOT available in the NPL boundary GDB. If needed, it must be sourced from a separate EPA dataset — SEMS (Superfund Enterprise Management System) public data or the CERCLIS database extract.

---

## Q5 — Geography Distance Test

**Status:** ✅ Verified on Railway PostGIS (2026-03-23)

```sql
SELECT ST_DWithin(
  ST_SetSRID(ST_MakePoint(-84.3963, 33.7749), 4326)::geography,
  ST_SetSRID(ST_MakePoint(-84.4500, 33.8000), 4326)::geography,
  10000
) AS within_10km,
ST_Distance(
  ST_SetSRID(ST_MakePoint(-84.3963, 33.7749), 4326)::geography,
  ST_SetSRID(ST_MakePoint(-84.4500, 33.8000), 4326)::geography
) AS distance_meters;
```

```
 within_10km | distance_meters
-------------+-----------------
 t           |   5699.60746534
```

`ST_DWithin` with `::geography` cast returns meters (not degrees). Confirmed working on Railway `postgres-postgis` service. This is the production PostGIS instance — no further verification needed.

---

## Summary — Blockers Before Build

| Item | Status |
|------|--------|
| Railway project `site-plain` | ✅ Created — project ID `bcd68885-1840-4be3-9c6c-75ced28d9cde` |
| PostGIS on Railway | ✅ Confirmed — `postgis/postgis:16-3.4`, PostGIS 3.4 on service `postgres-postgis` |
| NPL boundary data (local) | Available as GDB, geopandas-readable |
| NPL boundary geometry type | MultiPolygon, EPSG:4326 — confirmed |
| NPL site identifier field | `EPA_ID` |
| Human exposure JSON identifier | `epaid` (same format, lowercase) |
| EPA_ID join between datasets | Compatible — same 12-char format |
| Contamination type field | Does not exist in NPL boundary GDB |
| ST_DWithin geography distance | ✅ Verified on Railway PostGIS — 5,699.61m, `within_10km = t` |
| Local PostgreSQL for dev | Not available — use Railway postgres-postgis public URL for dev testing |

**Note:** No contamination type/chemical field in the NPL boundary data. The spec does not require it — contamination type is not shown in the tool. This is a resolved data limitation, not a build blocker.
