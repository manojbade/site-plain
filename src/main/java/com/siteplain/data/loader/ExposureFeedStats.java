package com.siteplain.data.loader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the exposure loader saw in one feed file, and the content checks it must pass before the
 * nplstatus checks in DataBootstrapService. Identity fields allow the converter's 0.5% gap; the status
 * code allows 2% because EPA publishes a few sites with no assessment (3 of 1,905 on 2026-10-10).
 */
public class ExposureFeedStats {

    static final double MAX_NULL_RATE = 0.005;
    static final double MAX_NULL_STATUS_CODE_RATE = 0.02;
    static final int MIN_ROWS = 1500;
    // Rows whose state_code is not a known code and whose EPA ID prefix is not one either.
    static final String STATE_FIELD = "state (state_code or EPA ID prefix)";
    static final List<String> REQUIRED_FIELDS = List.of("epaid", "nplstatus", "sitename", STATE_FIELD, "humexposurestscode");

    Boolean success;
    boolean dataArray;
    int rows;
    Integer metaTotal;
    int staged;
    final Map<String, Tally> missing = new LinkedHashMap<>();
    final Tally duplicates = new Tally();
    final Map<String, Integer> unknownStatusCodes = new TreeMap<>();

    ExposureFeedStats() {
        REQUIRED_FIELDS.forEach(field -> missing.put(field, new Tally()));
    }

    public int staged() {
        return staged;
    }

    /** humexposurestscode values ExposureStatusMapper has no label for, with row counts (logged, not fatal). */
    public Map<String, Integer> unknownStatusCodes() {
        return unknownStatusCodes;
    }

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (Boolean.FALSE.equals(success)) {
            problems.add("feed says success=false");
        }
        if (!dataArray) {
            problems.add("no data array");
            return problems;
        }
        if (metaTotal != null && metaTotal != rows) {
            problems.add("meta.total_data is " + metaTotal + " but the data array has " + rows + " rows");
        }
        if (rows == 0) {
            problems.add("data array is empty");
            return problems;
        }
        missing.forEach((field, tally) -> {
            double limit = "humexposurestscode".equals(field) ? MAX_NULL_STATUS_CODE_RATE : MAX_NULL_RATE;
            double rate = (double) tally.count() / rows;
            if (rate > limit) {
                problems.add(String.format(Locale.ROOT, "%s missing or blank on %d of %d rows (%.2f%%, limit %.2f%%)",
                        field, tally.count(), rows, rate * 100, limit * 100));
            }
        });
        if (duplicates.count() > 0) {
            problems.add(duplicates.count() + " duplicate epaid values" + duplicates.examples());
        }
        if (staged <= MIN_ROWS) {
            problems.add("only " + staged + " rows with an epaid, expected more than " + MIN_ROWS);
        }
        return problems;
    }
}
