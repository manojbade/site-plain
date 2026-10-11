package com.siteplain.web.controller;

import com.siteplain.service.FeedbackService;
import com.siteplain.web.BadRequestException;
import com.siteplain.web.RequestInput;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class FeedbackController {

    /**
     * Page types the feedback page may be linked from. A link with any other type shows the general
     * form; a POST with any other type is rejected.
     */
    private static final Set<String> PAGE_TYPES = Set.of("general", "home", "results", "site", "state", "search", "about");
    private static final Pattern EPA_ID = Pattern.compile("[A-Za-z0-9]{1,20}");
    /** Hidden from people (display: none, out of the tab order); bots that fill every field fill it. */
    static final String HONEYPOT_FIELD = "website";
    static final String EMPTY_FEEDBACK_MESSAGE = "Choose Yes or No, or write a message, before sending.";
    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

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

    /**
     * Saves one feedback row. Anything a real form cannot send (a missing or unknown page type, a
     * malformed EPA ID, a comment over 500 characters) is a 400, never a silent drop. Feedback with
     * no answer and no comment says nothing: a 400 that shows the feedback form again with a note,
     * and no row. A filled-in honeypot field marks a bot: nothing is saved, but it still gets the
     * thank-you page so it learns nothing. A failed insert is a real server error (500).
     */
    @PostMapping("/feedback")
    public ModelAndView submitFeedback(@RequestParam String pageType,
                                       @RequestParam(required = false) String epaId,
                                       @RequestParam(required = false) Boolean helpful,
                                       @RequestParam(required = false) String comments,
                                       @RequestParam(name = HONEYPOT_FIELD, required = false) String honeypot,
                                       HttpServletResponse response) {
        String type = RequestInput.maxLength(pageType, RequestInput.MAX_SHORT_PARAM, "pageType").trim().toLowerCase(Locale.ROOT);
        if (!PAGE_TYPES.contains(type)) {
            throw new BadRequestException("unknown pageType");
        }
        String id = epaId == null || epaId.isBlank() ? null : epaId.trim();
        if (id != null && !EPA_ID.matcher(id).matches()) {
            throw new BadRequestException("malformed epaId");
        }
        // Browsers count a line break as one character against maxlength but send it as CRLF.
        String text = comments == null ? null : comments.replace("\r\n", "\n").strip();
        RequestInput.maxLength(text, RequestInput.MAX_FEEDBACK_COMMENTS, "comments");
        RedirectView redirectView = new RedirectView("/feedback/thanks", true);
        redirectView.setExposeModelAttributes(false);
        if (honeypot != null && !honeypot.isEmpty()) {
            log.info("Feedback discarded: honeypot field filled");
            return new ModelAndView(redirectView);
        }
        if (helpful == null && (text == null || text.isEmpty())) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            ModelAndView form = new ModelAndView("feedback");
            form.addObject("pageType", type);
            form.addObject("epaId", id == null ? null : id.toUpperCase(Locale.ROOT));
            form.addObject("feedbackError", EMPTY_FEEDBACK_MESSAGE);
            return form;
        }
        feedbackService.save(type, id == null ? null : id.toUpperCase(Locale.ROOT), helpful,
                text == null || text.isEmpty() ? null : text);
        return new ModelAndView(redirectView);
    }

    @GetMapping("/feedback/thanks")
    public String thanks() {
        return "feedback-thanks";
    }
}
