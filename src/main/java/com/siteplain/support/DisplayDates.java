package com.siteplain.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class DisplayDates {

    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US);

    private DisplayDates() {
    }

    public static String format(LocalDate date) {
        return date == null ? null : date.format(LONG_DATE);
    }
}
