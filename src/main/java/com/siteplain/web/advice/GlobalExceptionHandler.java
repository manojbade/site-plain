package com.siteplain.web.advice;

import com.siteplain.service.ServiceBusyException;
import com.siteplain.web.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Client mistakes (missing or malformed parameters, the wrong method) get a 4xx and one WARN line
 * naming the exception type, never a stack trace or the submitted value. Everything else is a real
 * server error: 500, logged at ERROR with the stack trace.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoResourceFoundException.class)
    public String handleNotFound(NoResourceFoundException exception, HttpServletResponse response, Model model) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        model.addAttribute("message", "The requested page could not be found.");
        return "error/not-found";
    }

    @ExceptionHandler({
            BadRequestException.class,
            ServletRequestBindingException.class,
            TypeMismatchException.class,
            BindException.class,
            HttpMessageNotReadableException.class
    })
    public String handleBadRequest(Exception exception, HttpServletRequest request, HttpServletResponse response, Model model) {
        logClientError(HttpServletResponse.SC_BAD_REQUEST, exception, request);
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        model.addAttribute("status", HttpServletResponse.SC_BAD_REQUEST);
        return "error/not-found";
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public String handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception,
                                        HttpServletRequest request,
                                        HttpServletResponse response,
                                        Model model) {
        logClientError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, exception, request);
        Set<HttpMethod> supported = exception.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            response.setHeader(HttpHeaders.ALLOW, supported.stream().map(HttpMethod::name).sorted().collect(Collectors.joining(", ")));
        }
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        model.addAttribute("status", HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        return "error/not-found";
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public String handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception,
                                            HttpServletRequest request,
                                            HttpServletResponse response,
                                            Model model) {
        logClientError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, exception, request);
        response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
        model.addAttribute("status", HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
        return "error/not-found";
    }

    /** The client accepts none of the types the endpoint produces (an HTML-only Accept on the PDF), so no body. */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> handleNotAcceptable(HttpMediaTypeNotAcceptableException exception, HttpServletRequest request) {
        logClientError(HttpServletResponse.SC_NOT_ACCEPTABLE, exception, request);
        return ResponseEntity.status(HttpServletResponse.SC_NOT_ACCEPTABLE).build();
    }

    /** A rate limit (429) or a full concurrency cap (503): Retry-After and the plain busy page, no stack trace. */
    @ExceptionHandler(ServiceBusyException.class)
    public String handleBusy(ServiceBusyException exception, HttpServletResponse response, Model model) {
        response.setStatus(exception.status());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
        model.addAttribute("status", exception.status());
        return "error/not-found";
    }

    @ExceptionHandler(Exception.class)
    public String handleUnexpected(Exception exception, HttpServletResponse response, Model model) {
        log.error("Unhandled application error", exception);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        // The shared error template uses this to show server-error wording instead of "Page not found".
        model.addAttribute("status", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        model.addAttribute("message", "The request could not be completed.");
        return "error/not-found";
    }

    private static void logClientError(int status, Exception exception, HttpServletRequest request) {
        // The path only: the query string can hold an address.
        String detail = exception instanceof BadRequestException ? ": " + exception.getMessage() : "";
        log.warn("Client error {} on {} {} ({}{})", status, request.getMethod(), request.getRequestURI(),
                exception.getClass().getSimpleName(), detail);
    }
}
