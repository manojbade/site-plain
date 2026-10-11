package com.siteplain.web.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Replaces Spring Boot's BasicErrorController for errors that never reach a controller (the
 * container rejecting a malformed request, say). Always the plain HTML error page with the real
 * status. A direct request for /error is a missing page, not Boot's status 999 JSON.
 */
@Controller
public class SitePlainErrorController implements ErrorController {

    @RequestMapping("/error")
    public String error(HttpServletRequest request, HttpServletResponse response, Model model) {
        int status = statusOf(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
        response.setStatus(status);
        model.addAttribute("status", status);
        return "error/not-found";
    }

    /**
     * Which wording the error page shows for a status: "busy" (429, 503), "server" (other 5xx),
     * "request" (other 4xx) or "missing" (404, or no status).
     */
    public static String kind(Object status) {
        if (!(status instanceof Integer code) || code == HttpServletResponse.SC_NOT_FOUND) {
            return "missing";
        }
        if (code == 429 || code == HttpServletResponse.SC_SERVICE_UNAVAILABLE) {
            return "busy";
        }
        if (code >= 500) {
            return "server";
        }
        return code >= 400 ? "request" : "missing";
    }

    static int statusOf(Object attribute) {
        if (attribute instanceof Integer code && code >= 400 && code <= 599) {
            return code;
        }
        return HttpServletResponse.SC_NOT_FOUND;
    }
}
