package com.siteplain.data.loader;

import java.util.List;

/** A downloaded source failed validation. The message lists every check that failed. */
public class SourceRejectedException extends RuntimeException {

    private final List<String> problems;

    public SourceRejectedException(String source, List<String> problems) {
        super(source + " rejected: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
