package com.siteplain.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Thymeleaf 3.1 logs a "Deprecated unwrapped fragment expression" WARN on every render of a
 * th:replace or th:insert whose value is a bare "template :: fragment". Those lines once made up most
 * of the production log, so every fragment reference must be wrapped in ~{...} (or be a ${...} variable).
 */
class TemplateSyntaxTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Pattern FRAGMENT_ATTRIBUTE = Pattern.compile("th:(replace|insert|include)=\"([^\"]*)\"");

    @Test
    void everyFragmentReferenceIsWrapped() throws IOException {
        List<String> unwrapped = new ArrayList<>();
        int references = 0;
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".html")).toList()) {
                Matcher matcher = FRAGMENT_ATTRIBUTE.matcher(Files.readString(file));
                while (matcher.find()) {
                    references++;
                    String value = matcher.group(2).strip();
                    if (!value.startsWith("~{") && !value.startsWith("${")) {
                        unwrapped.add(TEMPLATES.relativize(file) + ": " + matcher.group());
                    }
                }
            }
        }
        assertThat(references).isGreaterThan(30);
        assertThat(unwrapped).isEmpty();
    }
}
