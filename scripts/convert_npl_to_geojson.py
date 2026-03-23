"""
Convert EPA NPL Boundary GDB to deduplicated GeoJSON for site-plain.

Input:  NPL_Boundaries.gdb  (layer SITE_BOUNDARIES_SF)
Output: npl_site_boundaries.geojson  (one polygon per EPA_ID)

Deduplication: multiple polygons per EPA_ID are resolved by priority order.
The polygon with the highest-priority SITE_FEATURE_TYPE wins.

Priority order (highest first):
  1. Comprehensive Site Area
  2. Total Site Polygon/OU Aggregation
  3. Site Boundary
  4. Current Ground Boundary
  5. OU Boundary Aggregation
  6. Extent of Contamination
  7. Contamination Boundary (Groundwater)
  8. Contamination Boundary
  9. Other

Usage:
    pip install geopandas pyogrio
    python3 scripts/convert_npl_to_geojson.py

Output file: scripts/npl_site_boundaries.geojson
Upload that file to GitHub releases and set SITEPLAIN_DATA_NPL_BOUNDARIES_URL accordingly.
"""

import json
import sys
from pathlib import Path

import geopandas as gpd

GDB_PATH = Path(__file__).parent.parent / "Info" / "superfund" / "superfund_audit_data" / "NPL_Boundaries" / "NPL_Boundaries.gdb"
GDB_PATH_ALT = Path("/Users/manojbade/workspace/Info/superfund/superfund_audit_data/NPL_Boundaries/NPL_Boundaries.gdb")
LAYER = "SITE_BOUNDARIES_SF"
OUTPUT_PATH = Path(__file__).parent / "npl_site_boundaries.geojson"

PRIORITY = {
    "Comprehensive Site Area": 1,
    "Total Site Polygon/OU Aggregation": 2,
    "Site Boundary": 3,
    "Current Ground Boundary": 4,
    "OU Boundary Aggregation": 5,
    "Extent of Contamination": 6,
    "Contamination Boundary (Groundwater)": 7,
    "Contamination Boundary": 8,
    "Other": 9,
}

def resolve_gdb_path():
    if GDB_PATH.exists():
        return GDB_PATH
    if GDB_PATH_ALT.exists():
        return GDB_PATH_ALT
    print(f"ERROR: GDB not found at {GDB_PATH} or {GDB_PATH_ALT}", file=sys.stderr)
    sys.exit(1)

def priority_rank(feature_type):
    if feature_type is None:
        return 99
    return PRIORITY.get(str(feature_type).strip(), 99)

def main():
    gdb_path = resolve_gdb_path()
    print(f"Reading {gdb_path} layer={LAYER} ...")
    gdf = gpd.read_file(gdb_path, layer=LAYER, engine="pyogrio")
    print(f"  Total features: {len(gdf)}")
    print(f"  CRS: {gdf.crs}")

    # Ensure WGS84
    if gdf.crs is None or gdf.crs.to_epsg() != 4326:
        print("  Reprojecting to EPSG:4326 ...")
        gdf = gdf.to_crs(epsg=4326)

    # Normalize EPA_ID to uppercase
    gdf["EPA_ID"] = gdf["EPA_ID"].str.strip().str.upper()

    # Drop rows with null EPA_ID or null geometry
    before = len(gdf)
    gdf = gdf[gdf["EPA_ID"].notna() & gdf["geometry"].notna()]
    dropped = before - len(gdf)
    if dropped:
        print(f"  Dropped {dropped} rows with null EPA_ID or geometry")

    # Assign priority rank for deduplication
    gdf["_priority"] = gdf["SITE_FEATURE_TYPE"].apply(priority_rank)

    # Keep lowest priority rank (highest priority) per EPA_ID
    gdf = gdf.sort_values("_priority").drop_duplicates(subset="EPA_ID", keep="first")
    print(f"  Unique sites after deduplication: {len(gdf)}")

    # Select and rename columns for output
    cols = {
        "EPA_ID": "EPA_ID",
        "SITE_NAME": "SITE_NAME",
        "STATE_CODE": "STATE_CODE",
        "NPL_STATUS_CODE": "NPL_STATUS_CODE",
        "URL_ALIAS_TXT": "URL_ALIAS_TXT",
    }
    out = gdf[[c for c in cols if c in gdf.columns] + ["geometry"]].copy()
    out = out.rename(columns=cols)

    # Normalize string columns
    for col in ["EPA_ID", "SITE_NAME", "STATE_CODE", "NPL_STATUS_CODE"]:
        if col in out.columns:
            out[col] = out[col].where(out[col].notna(), None)

    # Final NPL only
    final_count = (out["NPL_STATUS_CODE"] == "F").sum() if "NPL_STATUS_CODE" in out.columns else "unknown"
    print(f"  Final NPL sites (status='F'): {final_count}")

    print(f"Writing {OUTPUT_PATH} ...")
    out.to_file(OUTPUT_PATH, driver="GeoJSON")
    size_mb = OUTPUT_PATH.stat().st_size / (1024 * 1024)
    print(f"Done. {OUTPUT_PATH} ({size_mb:.1f} MB)")
    print()
    print("Next steps:")
    print("  1. Upload npl_site_boundaries.geojson to GitHub releases")
    print("  2. Set SITEPLAIN_DATA_NPL_BOUNDARIES_URL to the release asset URL in Railway")
    print("  3. Set SITEPLAIN_DATA_BOOTSTRAP_ENABLED=true on first deploy")

if __name__ == "__main__":
    main()
