package com.siteplain.service;

import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.domain.model.NplLookupResult;
import com.siteplain.domain.model.NplSite;
import com.siteplain.support.ExposureStatusMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class NplLookupService {

    private final JdbcTemplate jdbcTemplate;

    public NplLookupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 3 miles, the lookup radius. */
    static final double RADIUS_METERS = 4828.032;

    /**
     * Shortest length of one degree of latitude (at the equator) and of one degree of longitude at
     * the equator, on the WGS 84 ellipsoid ST_DWithin uses. Dividing a distance by them gives the
     * largest number of degrees that distance can span.
     */
    private static final double MIN_METERS_PER_DEGREE_LAT = 110_574.0;
    private static final double MIN_METERS_PER_DEGREE_LNG_AT_EQUATOR = 111_319.0;
    /** Safety factor on the search box, far more than the curvature and ellipsoid terms it covers. */
    private static final double BOX_MARGIN = 1.1;

    private static final String LOOKUP_SELECT = """
            SELECT
              a.epa_id,
              a.site_name,
              a.state_code,
              a.npl_status,
              a.epa_url,
              ST_Distance(
                a.geom::geography,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
              ) AS distance_meters,
              a.humexposurestscode,
              a.humanexposurepathdesc
            FROM active_npl_sites a
            WHERE
              a.geom IS NOT NULL
            """;

    /**
     * Sites at the same distance (overlapping boundaries an address is inside of, say) are ordered
     * by EPA ID, so their order no longer depends on the query plan.
     */
    private static final String EXACT_DISTANCE_FILTER = """
              AND ST_DWithin(
                a.geom::geography,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                ?
              )
            ORDER BY distance_meters ASC, a.epa_id ASC
            """;

    /**
     * Cheap first pass: only boundaries whose bounding box touches a lat/lng box around the point
     * that is sure to hold the whole radius. The geography index's own box is wider, and large
     * polygons that pass it then cost a full ST_DWithin each; the planar box test rejects them from
     * the stored bounding box. The exact geography ST_DWithin still decides. On prod-like data the
     * results are identical over 11,248 test points, and the worst case measured (Newark, NJ) drops
     * from about 136 ms to about 9 ms; typical lookups were already under 10 ms.
     */
    private static final String BOX_PREFILTER = """
              AND a.geom && ST_Expand(ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?)
            """;

    public NplLookupResult findSitesNear(GeocodedAddress address) {
        if (address == null || !address.resolved()) {
            return new NplLookupResult(address, List.of(), null, true);
        }
        double lng = address.longitude();
        double lat = address.latitude();
        List<Object> args = new ArrayList<>(List.of(lng, lat));
        StringBuilder sql = new StringBuilder(LOOKUP_SELECT);
        double[] box = searchBoxDegrees(lat, lng);
        if (box != null) {
            sql.append(BOX_PREFILTER);
            args.addAll(List.of(lng, lat, box[1], box[0]));
        }
        sql.append(EXACT_DISTANCE_FILTER);
        args.addAll(List.of(lng, lat, RADIUS_METERS));
        List<NplSite> sites = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            NplSite site = mapSite(rs);
            site.setDistanceMeters(rs.getDouble("distance_meters"));
            return site;
        }, args.toArray());
        Double nearestMiles = sites.isEmpty() ? null : sites.getFirst().getDistanceMeters() / 1609.344;
        return new NplLookupResult(address, sites, nearestMiles, false);
    }

    /**
     * Half the height and width, in degrees, of a lat/lng box around the point that contains every
     * point within RADIUS_METERS: {dLat, dLng}. Longitude degrees shrink toward the poles, so the
     * width uses the latitude of the box edge nearest the pole. Null where a plain lat/lng box does
     * not work, near a pole or across the 180th meridian (the western Aleutians), and the query then
     * runs on the geography filter alone.
     */
    static double[] searchBoxDegrees(double lat, double lng) {
        double dLat = RADIUS_METERS / MIN_METERS_PER_DEGREE_LAT * BOX_MARGIN;
        double poleward = Math.abs(lat) + dLat;
        if (poleward >= 89.0) {
            return null;
        }
        double dLng = RADIUS_METERS / (MIN_METERS_PER_DEGREE_LNG_AT_EQUATOR * Math.cos(Math.toRadians(poleward))) * BOX_MARGIN;
        if (Math.abs(lng) + dLng >= 180.0) {
            return null;
        }
        return new double[] {dLat, dLng};
    }

    /**
     * Active sites in a state that EPA has published no boundary for. They cannot be part of a
     * distance result, so result pages name them instead of silently leaving them out.
     */
    public List<NplSite> findActiveSitesWithoutBoundary(String stateCode) {
        if (stateCode == null || stateCode.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT a.epa_id, a.site_name, a.state_code, a.npl_status, a.epa_url,
                       a.humexposurestscode, a.humanexposurepathdesc
                FROM active_npl_sites a
                WHERE a.geom IS NULL
                  AND a.state_code = ?
                ORDER BY lower(a.site_name) ASC, a.epa_id ASC
                """, (rs, rowNum) -> mapSite(rs), stateCode.trim().toUpperCase(Locale.ROOT));
    }

    public int countActiveSitesWithoutBoundary() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM active_npl_sites WHERE geom IS NULL", Integer.class);
        return count == null ? 0 : count;
    }

    private NplSite mapSite(ResultSet rs) throws SQLException {
        NplSite site = new NplSite();
        site.setEpaId(rs.getString("epa_id"));
        site.setSiteName(rs.getString("site_name"));
        site.setStateCode(rs.getString("state_code"));
        site.setNplStatusCode(rs.getString("npl_status"));
        site.setEpaUrl(rs.getString("epa_url"));
        String statusCode = rs.getString("humexposurestscode");
        site.setExposureStatusCode(statusCode);
        site.setExposureStatusLabel(ExposureStatusMapper.label(statusCode));
        site.setExposureStatusColor(ExposureStatusMapper.color(statusCode));
        site.setExposurePathwayDescription(rs.getString("humanexposurepathdesc"));
        return site;
    }
}
