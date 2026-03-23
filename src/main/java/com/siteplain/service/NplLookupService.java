package com.siteplain.service;

import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.domain.model.NplLookupResult;
import com.siteplain.domain.model.NplSite;
import com.siteplain.support.ExposureStatusMapper;
import java.util.List;
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
                  s.epa_id,
                  s.site_name,
                  s.state_code,
                  s.npl_status_code,
                  s.epa_url,
                  ST_Distance(
                    s.geom::geography,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                  ) AS distance_meters,
                  h.humexposurestscode,
                  h.humanexposurepathdesc
                FROM npl_site_boundaries s
                LEFT JOIN npl_human_exposure h ON h.epa_id = s.epa_id
                WHERE
                  s.npl_status_code = 'F'
                  AND ST_DWithin(
                    s.geom::geography,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    4828.032
                  )
                ORDER BY distance_meters ASC
                """, (rs, rowNum) -> {
            NplSite site = new NplSite();
            site.setEpaId(rs.getString("epa_id"));
            site.setSiteName(rs.getString("site_name"));
            site.setStateCode(rs.getString("state_code"));
            site.setNplStatusCode(rs.getString("npl_status_code"));
            site.setEpaUrl(rs.getString("epa_url"));
            site.setDistanceMeters(rs.getDouble("distance_meters"));
            String statusCode = rs.getString("humexposurestscode");
            site.setExposureStatusCode(statusCode);
            site.setExposureStatusLabel(ExposureStatusMapper.label(statusCode));
            site.setExposureStatusColor(ExposureStatusMapper.color(statusCode));
            site.setExposurePathwayDescription(rs.getString("humanexposurepathdesc"));
            return site;
        }, address.longitude(), address.latitude(), address.longitude(), address.latitude());
        Double nearestMiles = sites.isEmpty() ? null : sites.getFirst().getDistanceMeters() / 1609.344;
        return new NplLookupResult(address, sites, nearestMiles, false);
    }
}
