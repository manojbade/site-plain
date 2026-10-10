package com.siteplain.data.loader;

import java.util.ArrayList;
import java.util.List;

/** Counts occurrences of one validation problem and keeps the first few examples for the message. */
final class Tally {

    private static final int MAX_EXAMPLES = 5;

    private int count;
    private final List<String> examples = new ArrayList<>();

    void add(String example) {
        count++;
        if (examples.size() < MAX_EXAMPLES) {
            examples.add(example);
        }
    }

    int count() {
        return count;
    }

    /** "(first: A, B, C, ...)" or an empty string when there is nothing to show. */
    String examples() {
        if (examples.isEmpty()) {
            return "";
        }
        return " (" + (count > examples.size() ? "first: " : "") + String.join(", ", examples)
                + (count > examples.size() ? ", ..." : "") + ")";
    }
}
