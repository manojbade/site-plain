package com.siteplain.service;

import com.siteplain.data.repository.FeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final FeedbackRepository feedbackRepository;

    public FeedbackService(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    /**
     * Stores feedback the controller has already validated (page type from a fixed list, EPA ID of
     * up to 20 letters and digits, comments of up to 500 characters). A failed insert propagates,
     * so the visitor sees an error instead of a thank-you for feedback that was lost.
     */
    public void save(String pageType, String epaId, Boolean helpful, String comments) {
        feedbackRepository.save(pageType, epaId, helpful, comments);
        log.info("Feedback: pageType={}, helpful={}", pageType, helpful);
    }
}
