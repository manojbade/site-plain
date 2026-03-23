package com.siteplain.service;

import com.siteplain.data.repository.LookupAuditRepository;
import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.domain.model.NplLookupResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final LookupAuditRepository lookupAuditRepository;

    public AuditService(LookupAuditRepository lookupAuditRepository) {
        this.lookupAuditRepository = lookupAuditRepository;
    }

    public void logLookup(GeocodedAddress address, NplLookupResult result) {
        try {
            lookupAuditRepository.insert(
                    address == null ? null : address.stateCode(),
                    result == null ? 0 : result.sites().size(),
                    result == null || result.nearestMiles() == null
                            ? null
                            : BigDecimal.valueOf(result.nearestMiles()).setScale(3, RoundingMode.HALF_UP),
                    address == null ? null : address.geocoderUsed(),
                    result != null && !result.unresolved()
            );
        } catch (DataAccessException ex) {
            log.warn("Lookup audit insert failed", ex);
        }
    }
}
