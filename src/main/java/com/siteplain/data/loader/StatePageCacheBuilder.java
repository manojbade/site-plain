package com.siteplain.data.loader;

import com.siteplain.data.repository.StatePageRepository;
import com.siteplain.support.StateNames;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class StatePageCacheBuilder {

    private final StatePageRepository statePageRepository;

    public StatePageCacheBuilder(StatePageRepository statePageRepository) {
        this.statePageRepository = statePageRepository;
    }

    /** Replaces the cache from site_seo_page_cache, so it must run after SeoPageCacheBuilder.rebuild(). */
    public int rebuild() {
        Map<String, String> namesByCode = new LinkedHashMap<>();
        for (String code : statePageRepository.findCachedStateCodes()) {
            namesByCode.put(code, StateNames.nameFor(code));
        }
        statePageRepository.clear();
        return statePageRepository.insertFromSiteCache(namesByCode);
    }
}
