package com.siteplain.web.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.siteplain.web.BadRequestException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.HttpRequestMethodNotSupportedException;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(Duration.ofMinutes(1), now::get);

    @Test
    void clientErrorLines_areThrottledPerStatusAndExceptionTypeWithACount(CapturedOutput output) {
        for (int i = 0; i < 50; i++) {
            badRequest("results link not valid");
            now.addAndGet(100);
        }
        // A different exception type, and a different status, each get their own line in the same minute.
        handler.handleBadRequest(new org.springframework.web.bind.MissingServletRequestParameterException("pageType", "String"),
                request("POST", "/feedback"), new MockHttpServletResponse(), new ExtendedModelMap());
        handler.handleMethodNotAllowed(new HttpRequestMethodNotSupportedException("GET"),
                request("GET", "/lookup"), new MockHttpServletResponse(), new ExtendedModelMap());

        assertThat(count(output, "Client error 400 on GET /results (BadRequestException: results link not valid)")).isEqualTo(1);
        assertThat(output.getOut()).contains("(BadRequestException: results link not valid) (1 since the last report)")
                .contains("Client error 400 on POST /feedback (MissingServletRequestParameterException) (1 since the last report)")
                .contains("Client error 405 on GET /lookup (HttpRequestMethodNotSupportedException) (1 since the last report)");

        // A minute after the first line, the next one reports everything held back since.
        now.addAndGet(60_000);
        badRequest("results link not valid");
        assertThat(count(output, "Client error 400 on GET /results (BadRequestException")).isEqualTo(2);
        assertThat(output.getOut()).contains("(BadRequestException: results link not valid) (50 since the last report)");
    }

    private void badRequest(String message) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.handleBadRequest(new BadRequestException(message), request("GET", "/results"), response, new ExtendedModelMap());
        assertThat(response.getStatus()).isEqualTo(400);
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static int count(CapturedOutput output, String text) {
        return output.getOut().split(Pattern.quote(text), -1).length - 1;
    }
}
