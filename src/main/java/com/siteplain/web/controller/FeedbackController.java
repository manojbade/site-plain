package com.siteplain.web.controller;

import com.siteplain.service.FeedbackService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping("/feedback")
    public RedirectView submitFeedback(@RequestParam String pageType,
                                       @RequestParam(required = false) String epaId,
                                       @RequestParam(required = false) Boolean helpful,
                                       @RequestParam(required = false) String comments) {
        feedbackService.save(pageType, epaId, helpful, comments);
        RedirectView redirectView = new RedirectView("/feedback/thanks", true);
        redirectView.setExposeModelAttributes(false);
        return redirectView;
    }

    @GetMapping("/feedback/thanks")
    @ResponseBody
    public String thanks() {
        return "Thank you for your feedback.";
    }
}
