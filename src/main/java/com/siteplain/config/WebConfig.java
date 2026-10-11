package com.siteplain.config;

import com.siteplain.web.limits.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final LimitsProperties limitsProperties;

    public WebConfig(LimitsProperties limitsProperties) {
        this.limitsProperties = limitsProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (limitsProperties.isRateLimitsEnabled()) {
            registry.addInterceptor(new RateLimitInterceptor(limitsProperties)).addPathPatterns(RateLimitInterceptor.paths());
        }
    }
}
