package com.siteplain.service;

import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.domain.model.NplLookupResult;
import com.siteplain.domain.model.NplSite;
import com.siteplain.support.ExposureStatusMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    public NplLookupResult findSitesNear(GeocodedAddress address) {
        if (address == null || !address.resolved()) {
            return new NplLookupResult(address, List.of(), null, true);
        }
        List<NplSite> sites = jdbcTemplate.query("""
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
                  AND ST_DWithin(
                    a.geom::geography,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    4828.032
                  )
                ORDER BY distance_meters ASC
                """, (rs, rowNum) -> {
            NplSite site = mapSite(rs);
            site.setDistanceMeters(rs.getDouble("distance_meters"));
            return site;
        }, address.longitude(), address.latitude(), address.longitude(), address.latitude());
        Double nearestMiles = sites.isEmpty() ? null : sites.getFirst().getDistanceMeters() / 1609.344;
        return new NplLookupResult(address, sites, nearestMiles, false);
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
