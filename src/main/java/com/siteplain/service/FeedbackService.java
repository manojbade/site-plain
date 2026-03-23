package com.siteplain.service;

import com.siteplain.data.repository.FeedbackRepository;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);
    private static final Pattern SAFE_TEXT = Pattern.compile("[^A-Za-z0-9_-]");

    private final FeedbackRepository feedbackRepository;

    public FeedbackService(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    public void save(String pageType, String epaId, Boolean helpful, String comments) {
        String sanitizedPageType = sanitize(pageType);
        String sanitizedEpaId = sanitize(epaId);
        String truncatedComments = truncate(comments, 500);
        try {
            feedbackRepository.save(sanitizedPageType, sanitizedEpaId, helpful, truncatedComments);
            log.info("Feedback: pageType={}, helpful={}", sanitizedPageType, helpful);
        } catch (DataAccessException ex) {
            log.warn("Feedback insert failed", ex);
        }
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return SAFE_TEXT.matcher(trimmed).replaceAll("");
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
