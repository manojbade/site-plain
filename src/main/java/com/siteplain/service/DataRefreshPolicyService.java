package com.siteplain.service;

import com.siteplain.config.DataProperties;
import com.siteplain.data.repository.NplBoundaryRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class DataRefreshPolicyService {

    private final DataProperties dataProperties;
    private final NplBoundaryRepository nplBoundaryRepository;

    public DataRefreshPolicyService(DataProperties dataProperties,
                                    NplBoundaryRepository nplBoundaryRepository) {
        this.dataProperties = dataProperties;
        this.nplBoundaryRepository = nplBoundaryRepository;
    }

    public boolean shouldRefreshBoundaries() {
        if (!nplBoundaryRepository.liveDataPresent()) {
            return true;
        }
        if (dataProperties.isRefreshOnStartup()) {
            return true;
        }
        return nplBoundaryRepository.lastLoadedAt()
                .map(loadedAt -> loadedAt.isBefore(LocalDateTime.now().minusDays(dataProperties.getMaxAgeDays())))
                .orElse(true);
    }
}
