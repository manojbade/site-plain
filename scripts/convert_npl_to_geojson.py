"""
Convert EPA NPL Boundary GDB to deduplicated GeoJSON for site-plain.

Input:  NPL_Boundaries.gdb  (layer SITE_BOUNDARIES_SF)
Output: npl_site_boundaries.geojson  (one MultiPolygon feature per EPA_ID)

Deduplication: multiple polygons per EPA_ID are resolved by priority order.
Only rows at the highest-priority SITE_FEATURE_TYPE present for a site are
kept, and when several rows tie at that priority their geometries are unioned
into one MultiPolygon, so the output covers the full designated site area.
Rows with a null or unmapped SITE_FEATURE_TYPE rank last (99).

State source-control areas are dropped before ranking: rows whose
SITE_FEATURE_NAME contains "Source Control" (for example "LDW Uplands Source
Control Area", WA Department of Ecology, 80.6 km2, and "Portland Harbor
Uplands ODEQ Source Control", Oregon DEQ, 32.6 km2). They are drainage basins
mapped for upland source control, not the NPL site footprint, and unioning
them put homes miles from the Lower Duwamish Waterway "inside" the site.

Union review gate: a tied union is flagged when one of its pieces adds more
than --union-review-km2 and grows the union of the other pieces by more than
UNION_REVIEW_MIN_RATIO. Each flagged site must be listed in REVIEWED_UNIONS
with the largest union area accepted after checking EPA's site description,
or the script fails. A reviewed site whose union grows past that area fails
too, so a new oversized piece in a later release is caught.

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

Attributes come from one row of the winning group, chosen deterministically:
non-null NPL_STATUS_CODE first, then non-null STATE_CODE, then the latest
LAST_CHANGE_DATE, then the lowest GDB feature id. Sites with a single winning
row keep that row's geometry unchanged unless it is invalid, in which case it
is repaired with make_valid. Every output geometry is a MultiPolygon.

Input checks: the script exits non-zero (and writes nothing) when the raw
layer has a null/blank EPA_ID, NPL_STATUS_CODE or STATE_CODE rate above
--max-null-rate, or when the Final (NPL_STATUS_CODE='F') site count falls
outside --min-final..--max-final, or when the union review gate fails. The
first two catch broken EPA releases such as the 2026-10-10 EDG file (239 null
STATE_CODE and 113 null NPL_STATUS_CODE rows).

Usage (Python 3.9 to 3.12, in a venv; see docs/data/README.md, "Converter setup"):
    python -m pip install --only-binary=:all: -r scripts/requirements.txt
    python scripts/convert_npl_to_geojson.py --gdb PATH/NPL_Boundaries.gdb [--output PATH]
    python scripts/convert_npl_to_geojson.py --self-test

Output file (default): scripts/npl_site_boundaries.geojson
Publish it as a new GitHub release and point SITEPLAIN_DATA_NPL_BOUNDARIES_URL at the asset
(docs/data/README.md, "Publish and load a new boundary release").
"""

import argparse
import sys
from pathlib import Path

import geopandas as gpd
import pandas as pd
from shapely import make_valid
from shapely.geometry import GeometryCollection, MultiPolygon, Polygon
from shapely.ops import unary_union

GDB_PATH = Path(__file__).parent.parent / "Info" / "superfund" / "superfund_audit_data" / "NPL_Boundaries" / "NPL_Boundaries.gdb"
GDB_PATH_ALT = Path("/Users/manojbade/workspace/Info/superfund/superfund_audit_data/NPL_Boundaries/NPL_Boundaries.gdb")
LAYER = "SITE_BOUNDARIES_SF"
OUTPUT_PATH = Path(__file__).parent / "npl_site_boundaries.geojson"

# Reference at 2026-07-11: 1,328 Final sites. The band allows normal EPA
# additions and deletions but rejects a release that loses or gains ~10%.
DEFAULT_MIN_FINAL = 1200
DEFAULT_MAX_FINAL = 1450
DEFAULT_MAX_NULL_RATE = 0.005

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

OUTPUT_COLUMNS = ["EPA_ID", "SITE_NAME", "STATE_CODE", "NPL_STATUS_CODE", "URL_ALIAS_TXT"]

EXCLUDED_FEATURE_NAME_PATTERNS = ["source control"]

# Areas for the union review gate are measured in EPSG:6933 (equal area, valid outside CONUS too).
AREA_CRS = "EPSG:6933"
DEFAULT_UNION_REVIEW_KM2 = 2.0
UNION_REVIEW_MIN_RATIO = 1.5

# Tied unions that trip the review gate, checked against EPA's site descriptions on the July 2026
# GDB: EPA_ID -> (largest accepted union km2, reason). Union km2 in July is in each reason.
REVIEWED_UNIONS = {
    "AK1570028646": (81.5, "Eielson AFB, 80.6 km2: the base boundary ('covers approximately 19,700 acres'), "
                           "plus a small PFAS piece inside it"),
    "IDD984666610": (9.6, "Eastern Michaud Flats, 9.4 km2: the FMC and Simplot OUs from the 2010 interim AROD"),
    "OR0000515759": (6.0, "Black Butte Mine, 5.9 km2: EPA's three OUs (mine area, creek reaches, reservoir)"),
    "ORSFN1002155": (18.6, "Portland Harbor, 18.2 km2 with the ODEQ source-control basin dropped: EPA's "
                           "2017 ROD in-water area plus the upland cleanup properties EPA filed with it"),
    "WAD980978753": (9.0, "Midnite Mine, 8.8 km2: the 2006 ROD's Mining Affected Area, which EPA describes as "
                          "contaminated by mine-related contaminants, around the mined area"),
    "WAD988466355": (50.5, "Moses Lake Wellfield, 49.9 km2: EPA's groundwater institutional control boundary, "
                           "already the shape in the live release; the 12 source areas lie inside it"),
}


class InputCheckError(Exception):
    pass


def resolve_gdb_path(cli_path):
    if cli_path:
        path = Path(cli_path)
        if path.exists():
            return path
        print(f"ERROR: GDB not found at {path}", file=sys.stderr)
        sys.exit(1)
    if GDB_PATH.exists():
        return GDB_PATH
    if GDB_PATH_ALT.exists():
        return GDB_PATH_ALT
    print(f"ERROR: GDB not found at {GDB_PATH} or {GDB_PATH_ALT}", file=sys.stderr)
    sys.exit(1)


def priority_rank(feature_type):
    if feature_type is None or (isinstance(feature_type, float) and pd.isna(feature_type)):
        return 99
    return PRIORITY.get(str(feature_type).strip(), 99)


def blank_to_none(value):
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return None
    text = str(value).strip()
    return text if text else None


def to_multipolygon(geom):
    """Repair an invalid geometry and return it as a MultiPolygon (or None if no area remains)."""
    if geom is None or geom.is_empty:
        return None
    if not geom.is_valid:
        geom = make_valid(geom)
    if isinstance(geom, MultiPolygon):
        return geom
    if isinstance(geom, Polygon):
        return MultiPolygon([geom])
    if isinstance(geom, GeometryCollection):
        polygons = []
        for part in geom.geoms:
            if isinstance(part, Polygon):
                polygons.append(part)
            elif isinstance(part, MultiPolygon):
                polygons.extend(part.geoms)
        return MultiPolygon(polygons) if polygons else None
    return None


def check_input(gdf, max_null_rate):
    """Raise InputCheckError when the raw layer looks broken."""
    total = len(gdf)
    if total == 0:
        raise InputCheckError("layer has no rows")
    problems = []
    for column in ["EPA_ID", "NPL_STATUS_CODE", "STATE_CODE"]:
        if column not in gdf.columns:
            problems.append(f"missing column {column}")
            continue
        nulls = int(gdf[column].map(blank_to_none).isna().sum())
        rate = nulls / total
        print(f"  Null/blank {column}: {nulls} of {total} rows ({rate:.2%})")
        if rate > max_null_rate:
            problems.append(f"{column} null/blank rate {rate:.2%} exceeds {max_null_rate:.2%} ({nulls} rows)")
    if problems:
        raise InputCheckError("; ".join(problems))


def check_output(out, min_final, max_final):
    final_count = int((out["NPL_STATUS_CODE"] == "F").sum())
    print(f"  Final NPL sites (status='F'): {final_count}")
    if final_count < min_final or final_count > max_final:
        raise InputCheckError(
            f"Final site count {final_count} is outside the expected band {min_final}..{max_final}")
    missing_state = int((out["NPL_STATUS_CODE"].eq("F") & out["STATE_CODE"].isna()).sum())
    if missing_state:
        raise InputCheckError(f"{missing_state} Final sites have no STATE_CODE")
    return final_count


def drop_excluded_features(gdf):
    """Drop rows whose SITE_FEATURE_NAME marks a non-site area such as a state source-control basin.

    Returns the kept rows and a list of (EPA_ID, _fid, SITE_FEATURE_NAME) for the dropped ones.
    Raises InputCheckError if that would leave a site with no rows, so it gets a human look.
    """
    names = gdf["SITE_FEATURE_NAME"].map(blank_to_none) if "SITE_FEATURE_NAME" in gdf.columns \
        else pd.Series([None] * len(gdf), index=gdf.index)
    lowered = names.map(lambda n: n.lower() if n else "")
    excluded = lowered.map(lambda n: any(pattern in n for pattern in EXCLUDED_FEATURE_NAME_PATTERNS))
    dropped = [(row.EPA_ID, int(row._fid), names[idx]) for idx, row in gdf[excluded].iterrows()]
    kept = gdf[~excluded]
    emptied = sorted(set(gdf["EPA_ID"]) - set(kept["EPA_ID"]))
    if emptied:
        raise InputCheckError(f"excluded features would remove every polygon of {', '.join(emptied)}")
    return kept, dropped


def deduplicate(gdf):
    """Return one row per EPA_ID: union of the top-priority polygons plus deterministic attributes.

    Expects columns EPA_ID, SITE_FEATURE_TYPE, the OUTPUT_COLUMNS, LAST_CHANGE_DATE (optional),
    _fid (stable source feature id) and geometry. Also returns the ids whose winning group had
    more than one polygon, the ids whose geometry needed repair, and for each tied id the list of
    (_fid, SITE_FEATURE_NAME, MultiPolygon) pieces that were unioned.
    """
    work = gdf.copy()
    work["_priority"] = work["SITE_FEATURE_TYPE"].map(priority_rank)
    best = work.groupby("EPA_ID")["_priority"].transform("min")
    work = work[work["_priority"] == best].copy()

    # Attribute row choice: most complete, then most recently changed, then lowest feature id.
    work["_status_missing"] = work["NPL_STATUS_CODE"].map(blank_to_none).isna().astype(int)
    work["_state_missing"] = work["STATE_CODE"].map(blank_to_none).isna().astype(int)
    if "LAST_CHANGE_DATE" in work.columns:
        work["_changed"] = pd.to_datetime(work["LAST_CHANGE_DATE"], errors="coerce", utc=True)
    else:
        work["_changed"] = pd.NaT
    work["_changed_missing"] = work["_changed"].isna().astype(int)
    work["_changed_key"] = -work["_changed"].map(lambda ts: 0 if pd.isna(ts) else ts.value)
    work = work.sort_values(
        ["EPA_ID", "_status_missing", "_state_missing", "_changed_missing", "_changed_key", "_fid"],
        kind="mergesort",
    )

    rows = []
    tied_ids = []
    repaired_ids = []
    tied_pieces = {}
    for epa_id, group in work.groupby("EPA_ID", sort=True):
        first = group.iloc[0]
        if len(group) > 1:
            tied_ids.append(epa_id)
            parts = [to_multipolygon(g) for g in group.geometry]
            names = group["SITE_FEATURE_NAME"] if "SITE_FEATURE_NAME" in group.columns else [None] * len(group)
            tied_pieces[epa_id] = [(int(fid), blank_to_none(name), part)
                                   for fid, name, part in zip(group["_fid"], names, parts) if part is not None]
            geom = to_multipolygon(unary_union([p for p in parts if p is not None]))
        else:
            source = first.geometry
            geom = to_multipolygon(source)
            if source is not None and not source.is_valid:
                repaired_ids.append(epa_id)
        if geom is None:
            continue
        row = {column: blank_to_none(first[column]) if column in group.columns else None for column in OUTPUT_COLUMNS}
        row["geometry"] = geom
        rows.append(row)

    out = gpd.GeoDataFrame(rows, columns=OUTPUT_COLUMNS + ["geometry"], geometry="geometry", crs=gdf.crs)
    return out, tied_ids, repaired_ids, tied_pieces


def check_unions(tied_pieces, crs, reviewed, min_added_km2):
    """Raise InputCheckError when a tied union is dominated by one piece and has not been reviewed.

    A piece is flagged when it adds more than min_added_km2 to the union of the other pieces and
    grows that union by more than UNION_REVIEW_MIN_RATIO. A flagged site passes only if it is in
    reviewed and its union is no larger than the reviewed area. Returns the per-site report lines.
    """
    report = []
    problems = []
    for epa_id, pieces in sorted(tied_pieces.items()):
        projected = gpd.GeoSeries([p for _, _, p in pieces], crs=crs).to_crs(AREA_CRS).tolist()
        union_km2 = unary_union(projected).area / 1e6
        worst = None
        for i, (fid, name, _) in enumerate(pieces):
            rest_km2 = unary_union(projected[:i] + projected[i + 1:]).area / 1e6
            added = union_km2 - rest_km2
            ratio = union_km2 / rest_km2 if rest_km2 > 0 else float("inf")
            if added > min_added_km2 and ratio > UNION_REVIEW_MIN_RATIO and (worst is None or added > worst[0]):
                worst = (added, ratio, fid, name)
        if worst is None:
            continue
        line = (f"{epa_id} union {union_km2:.3f} km2, piece fid {worst[2]} {worst[3]!r} adds "
                f"{worst[0]:.3f} km2 ({worst[1]:.1f}x the other pieces)")
        report.append(line)
        if epa_id not in reviewed:
            problems.append(f"unreviewed oversized union: {line}")
        elif union_km2 > reviewed[epa_id][0]:
            problems.append(f"union grew past its reviewed {reviewed[epa_id][0]} km2: {line}")
    if problems:
        raise InputCheckError("; ".join(problems))
    return report


def convert(gdb_path, output_path, min_final, max_final, max_null_rate, union_review_km2):
    print(f"Reading {gdb_path} layer={LAYER} ...")
    gdf = gpd.read_file(gdb_path, layer=LAYER, engine="pyogrio", fid_as_index=True)
    print(f"  Total features: {len(gdf)}")
    print(f"  CRS: {gdf.crs}")
    gdf["_fid"] = gdf.index.astype("int64")
    gdf = gdf.reset_index(drop=True)

    check_input(gdf, max_null_rate)

    # Ensure WGS84
    if gdf.crs is None or gdf.crs.to_epsg() != 4326:
        print("  Reprojecting to EPSG:4326 ...")
        gdf = gdf.to_crs(epsg=4326)

    # Normalize EPA_ID and codes to uppercase
    gdf["EPA_ID"] = gdf["EPA_ID"].map(blank_to_none).str.upper()
    for column in ["STATE_CODE", "NPL_STATUS_CODE"]:
        gdf[column] = gdf[column].map(blank_to_none).str.upper()

    # Drop rows with null EPA_ID or null geometry
    before = len(gdf)
    gdf = gdf[gdf["EPA_ID"].notna() & gdf["geometry"].notna()]
    dropped = before - len(gdf)
    if dropped:
        print(f"  Dropped {dropped} rows with null EPA_ID or geometry")

    gdf, dropped = drop_excluded_features(gdf)
    print(f"  Dropped {len(dropped)} source-control rows"
          + "".join(f"\n    {epa_id} fid {fid} {name!r}" for epa_id, fid, name in dropped))

    out, tied_ids, repaired_ids, tied_pieces = deduplicate(gdf)
    print(f"  Unique sites after deduplication: {len(out)}")
    print(f"  Sites with tied top-priority polygons (unioned): {len(tied_ids)}"
          + (f" {', '.join(tied_ids)}" if tied_ids else ""))
    print(f"  Sites with repaired invalid geometry: {len(repaired_ids)}"
          + (f" {', '.join(repaired_ids)}" if repaired_ids else ""))

    report = check_unions(tied_pieces, gdf.crs, REVIEWED_UNIONS, union_review_km2)
    print(f"  Reviewed oversized unions: {len(report)}" + "".join(f"\n    {line}" for line in report))

    # Report the Final NPL count. All statuses are written to the output;
    # the app decides which sites are active at query time, not here.
    check_output(out, min_final, max_final)

    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    print(f"Writing {output_path} ...")
    out.to_file(output_path, driver="GeoJSON")
    size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"Done. {output_path} ({size_mb:.1f} MB)")
    print()
    print("Next steps (docs/data/README.md, 'Publish and load a new boundary release'):")
    print("  1. Compare it with the live release")
    print("  2. Publish it as a new GitHub release (new tag data-YYYY-MM-DD; never replace an asset)")
    print("  3. Set SITEPLAIN_DATA_NPL_BOUNDARIES_URL to the new asset URL; that change alone reloads it")


def self_test():
    """Synthetic checks for dedup, union, determinism, repair and input gates. No GDB needed."""
    square = lambda x, y, s=1.0: Polygon([(x, y), (x + s, y), (x + s, y + s), (x, y + s)])
    bowtie = Polygon([(0, 0), (2, 2), (2, 0), (0, 2), (0, 0)])
    rows = [
        # A: two tied top-priority polygons plus a lower-priority one that must be ignored.
        ("A1", "Comprehensive Site Area", "Site A", "WA", "F", "2020-01-01", 10, square(0, 0)),
        ("A1", "Comprehensive Site Area", "Site A", "WA", "F", "2021-01-01", 11, square(5, 0)),
        ("A1", "Other", "Site A", "WA", "F", "2022-01-01", 12, square(50, 50, 10)),
        # B: single row, invalid self-intersecting geometry.
        ("B2", "Site Boundary", "Site B", "OR", "F", "2020-01-01", 20, bowtie),
        # C: tie where the first row by feature id lacks status; attributes must come from the complete row.
        ("C3", "Current Ground Boundary", "Site C", None, None, "2020-01-01", 30, square(0, 10)),
        ("C3", "Current Ground Boundary", "Site C", "AK", "F", "2019-01-01", 31, square(0, 12)),
    ]
    frame = gpd.GeoDataFrame(
        [dict(zip(["EPA_ID", "SITE_FEATURE_TYPE", "SITE_NAME", "STATE_CODE", "NPL_STATUS_CODE",
                   "LAST_CHANGE_DATE", "_fid"], r[:7]), URL_ALIAS_TXT=None, geometry=r[7]) for r in rows],
        geometry="geometry", crs="EPSG:4326")

    out, tied, repaired, pieces = deduplicate(frame)
    assert sorted(fid for fid, _, _ in pieces["A1"]) == [10, 11], pieces["A1"]
    by_id = {row.EPA_ID: row for row in out.itertuples()}
    assert sorted(by_id) == ["A1", "B2", "C3"], by_id.keys()
    assert tied == ["A1", "C3"], tied
    assert repaired == ["B2"], repaired
    assert abs(by_id["A1"].geometry.area - 2.0) < 1e-9, by_id["A1"].geometry.area
    assert by_id["A1"].geometry.geom_type == "MultiPolygon"
    assert by_id["B2"].geometry.is_valid and by_id["B2"].geometry.geom_type == "MultiPolygon"
    assert abs(by_id["B2"].geometry.area - 2.0) < 1e-9, by_id["B2"].geometry.area
    assert by_id["C3"].NPL_STATUS_CODE == "F" and by_id["C3"].STATE_CODE == "AK"

    # Determinism: shuffled input order gives the same output.
    shuffled, _, _, _ = deduplicate(frame.iloc[[5, 2, 0, 4, 3, 1]])
    assert shuffled.to_json() == out.to_json()

    # Input gates.
    try:
        check_input(frame, DEFAULT_MAX_NULL_RATE)
        raise AssertionError("null status/state rows should fail the input check")
    except InputCheckError:
        pass
    check_input(frame[frame["NPL_STATUS_CODE"].notna()], DEFAULT_MAX_NULL_RATE)
    try:
        check_output(out, DEFAULT_MIN_FINAL, DEFAULT_MAX_FINAL)
        raise AssertionError("3 Final sites should fail the Final count band")
    except InputCheckError:
        pass
    # Source-control basins are dropped before ranking, by SITE_FEATURE_NAME.
    named = frame.copy()
    named["SITE_FEATURE_NAME"] = ["A east", "A west", "A other", "B", None, "C"]
    basin = gpd.GeoDataFrame([dict(EPA_ID="A1", SITE_FEATURE_TYPE="Comprehensive Site Area", SITE_NAME="Site A",
                                   STATE_CODE="WA", NPL_STATUS_CODE="F", LAST_CHANGE_DATE="2023-01-01", _fid=13,
                                   URL_ALIAS_TXT=None, SITE_FEATURE_NAME="LDW Uplands Source Control Area",
                                   geometry=square(-20, -20, 40))], geometry="geometry", crs="EPSG:4326")
    kept, dropped = drop_excluded_features(pd.concat([named, basin], ignore_index=True))
    assert dropped == [("A1", 13, "LDW Uplands Source Control Area")], dropped
    kept_out, _, _, _ = deduplicate(kept)
    assert abs(kept_out.set_index("EPA_ID").loc["A1"].geometry.area - 2.0) < 1e-9
    try:
        drop_excluded_features(basin)
        raise AssertionError("a site with only source-control rows should fail")
    except InputCheckError:
        pass

    # Union review gate, with pieces about 1.2 km2 (0.01 degree) and 31 km2 (0.05 degree) at the equator.
    small, big = MultiPolygon([square(0, 0, 0.01)]), MultiPolygon([square(0.01, 0, 0.05)])
    even = {"E5": [(1, "east", small), (2, "west", MultiPolygon([square(0.01, 0, 0.01)]))]}
    assert check_unions(even, "EPSG:4326", {}, DEFAULT_UNION_REVIEW_KM2) == []
    lopsided = {"D4": [(1, "site", small), (2, "basin", big)]}
    for reviewed in ({}, {"D4": (10.0, "too small")}):
        try:
            check_unions(lopsided, "EPSG:4326", reviewed, DEFAULT_UNION_REVIEW_KM2)
            raise AssertionError(f"lopsided union should fail with reviewed={reviewed}")
        except InputCheckError:
            pass
    assert len(check_unions(lopsided, "EPSG:4326", {"D4": (40.0, "ok")}, DEFAULT_UNION_REVIEW_KM2)) == 1
    print("Self-test passed.")


def main():
    parser = argparse.ArgumentParser(description="Convert the EPA NPL boundary GDB to site-plain GeoJSON.")
    parser.add_argument("--gdb", help="Path to NPL_Boundaries.gdb (default: the Info/ workspace copy)")
    parser.add_argument("--output", default=str(OUTPUT_PATH), help=f"Output GeoJSON path (default: {OUTPUT_PATH})")
    parser.add_argument("--min-final", type=int, default=DEFAULT_MIN_FINAL, help="Minimum expected Final site count")
    parser.add_argument("--max-final", type=int, default=DEFAULT_MAX_FINAL, help="Maximum expected Final site count")
    parser.add_argument("--max-null-rate", type=float, default=DEFAULT_MAX_NULL_RATE,
                        help="Maximum share of raw rows with a null/blank EPA_ID, NPL_STATUS_CODE or STATE_CODE")
    parser.add_argument("--union-review-km2", type=float, default=DEFAULT_UNION_REVIEW_KM2,
                        help="Flag a tied union when one piece adds more than this many km2 (see REVIEWED_UNIONS)")
    parser.add_argument("--self-test", action="store_true", help="Run the built-in checks and exit")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return

    try:
        convert(resolve_gdb_path(args.gdb), args.output, args.min_final, args.max_final, args.max_null_rate,
                args.union_review_km2)
    except InputCheckError as error:
        print(f"ERROR: input check failed, nothing written: {error}", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
