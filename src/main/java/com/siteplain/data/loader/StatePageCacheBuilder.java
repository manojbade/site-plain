package com.siteplain.data.loader;

import com.siteplain.data.repository.StatePageRepository;
import com.siteplain.support.StateNames;
import org.springframework.stereotype.Component;

@Component
public class StatePageCacheBuilder {

    private final StatePageRepository statePageRepository;

    public StatePageCacheBuilder(StatePageRepository statePageRepository) {
        this.statePageRepository = statePageRepository;
    }

    public int rebuild() {
        statePageRepository.clear();
        int count = 0;
        for (StatePageRepository.StateSiteCountRow row : statePageRepository.findSiteCounts()) {
            statePageRepository.insert(row.stateCode(), StateNames.nameFor(row.stateCode()), row.siteCount());
            count++;
        }
        return count;
    }
}
