package com.siteplain.web.limits;

import com.siteplain.config.LimitsProperties;
import com.siteplain.service.ServiceBusyException;
import com.siteplain.support.ThrottledLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the per-client rate limit of the endpoint being called. A refusal is a ServiceBusyException
 * (429 with Retry-After and the plain busy page), logged as at most one WARN line a minute per
 * endpoint, without the client's address.
 *
 * <p>Default limits per client (LimitsProperties), each well above what a person does by hand:
 * POST /lookup 30 at once then 20 a minute; GET /results 60 then 60 a minute; GET /results/pdf 10
 * then 10 a minute; POST /feedback 5 then 5 an hour; GET /api/site-search/suggestions 60 then 120 a
 * minute; GET /search/sites 30 then 30 a minute.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /** The rate-limited endpoints, "METHOD path", to their limiter. */
    private final Map<String, RateLimiter> limiters;
    private final String clientIpHeader;
    private final ThrottledLog refusals = new ThrottledLog(log, 60_000, System::currentTimeMillis);

    public RateLimitInterceptor(LimitsProperties properties) {
        this(properties, System::nanoTime, Clock.systemUTC());
    }

    RateLimitInterceptor(LimitsProperties properties, java.util.function.LongSupplier nanoTime, Clock clock) {
        int max = properties.getMaxTrackedClients();
        this.limiters = Map.of(
                "POST /lookup", new RateLimiter("lookup", properties.getLookup(), max, nanoTime, clock),
                "GET /results", new RateLimiter("results", properties.getResults(), max, nanoTime, clock),
                "GET /results/pdf", new RateLimiter("results PDF", properties.getPdf(), max, nanoTime, clock),
                "POST /feedback", new RateLimiter("feedback", properties.getFeedback(), max, nanoTime, clock),
                "GET /api/site-search/suggestions", new RateLimiter("site suggestions", properties.getSuggestions(), max, nanoTime, clock),
                "GET /search/sites", new RateLimiter("site search", properties.getSearch(), max, nanoTime, clock));
        this.clientIpHeader = properties.getClientIpHeader();
    }

    /** The paths the interceptor is registered for. */
    public static String[] paths() {
        return new String[] {"/lookup", "/results", "/results/pdf", "/feedback", "/api/site-search/suggestions", "/search/sites"};
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = "HEAD".equals(request.getMethod()) ? "GET" : request.getMethod();
        RateLimiter limiter = limiters.get(method + " " + request.getRequestURI());
        if (limiter == null) {
            return true;
        }
        RateLimiter.Decision decision = limiter.tryAcquire(ClientAddress.of(request, clientIpHeader));
        if (!decision.allowed()) {
            refusals.warn(limiter.name(), "Rate limit refused " + limiter.name() + " requests");
            throw new ServiceBusyException(ServiceBusyException.TOO_MANY_REQUESTS, decision.retryAfterSeconds(),
                    limiter.name() + " rate limit");
        }
        return true;
    }
}
