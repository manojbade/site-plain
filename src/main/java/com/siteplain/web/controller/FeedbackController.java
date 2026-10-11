package com.siteplain.web.controller;

import com.siteplain.service.FeedbackService;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class FeedbackController {

    /** Page types the feedback page may be linked from; anything else is filed as general feedback. */
    private static final Set<String> PAGE_TYPES = Set.of("general", "home", "results", "site", "state", "search", "about");
    private static final Pattern EPA_ID = Pattern.compile("[A-Za-z0-9]{1,20}");

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping("/feedback")
    public String showFeedback(@RequestParam(required = false) String pageType,
                               @RequestParam(required = false) String epaId,
                               Model model) {
        String type = pageType == null ? "general" : pageType.trim().toLowerCase(Locale.ROOT);
        model.addAttribute("pageType", PAGE_TYPES.contains(type) ? type : "general");
        model.addAttribute("epaId", epaId != null && EPA_ID.matcher(epaId.trim()).matches()
                ? epaId.trim().toUpperCase(Locale.ROOT)
                : null);
        return "feedback";
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
    public String thanks() {
        return "feedback-thanks";
    }
}
