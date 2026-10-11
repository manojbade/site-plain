package com.siteplain.support;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Free text from EPA feeds, such as the human exposure pathway description. The feed marks line
 * breaks with &lt;br/&gt; and has no other markup, but it is upstream data, so pages never render it
 * as HTML: it is split on the line break tags here, and each line is escaped by the template
 * (th:text) with a real line break between lines. Any other tag shows as literal text.
 */
public final class EpaText {

    private static final Pattern LINE_BREAK = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);

    private EpaText() {
    }

    /**
     * The text between line break tags, in order, keeping empty lines so two breaks in a row still
     * render as a blank line. Empty for null or blank text.
     */
    public static List<String> lines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(LINE_BREAK.split(text, -1));
    }
}
