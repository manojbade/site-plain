package com.siteplain.data.loader;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExposureFeedStatsTest {

    /** The 2026-10-10 feed: 1,905 rows, 3 with no humexposurestscode. */
    private static ExposureFeedStats octoberFeed() {
        ExposureFeedStats stats = new ExposureFeedStats();
        stats.success = true;
        stats.dataArray = true;
        stats.rows = 1905;
        stats.metaTotal = 1905;
        stats.staged = 1905;
        for (int i = 0; i < 3; i++) {
            stats.missing.get("humexposurestscode").add("X" + i);
        }
        return stats;
    }

    @Test
    void acceptsTheOctoberFeed() {
        assertThat(octoberFeed().problems()).isEmpty();
    }

    @Test
    void rejectsARenamedStatusCodeField() {
        ExposureFeedStats stats = octoberFeed();
        for (int i = 3; i < 1905; i++) {
            stats.missing.get("humexposurestscode").add("X" + i);
        }
        assertThat(stats.problems()).containsExactly(
                "humexposurestscode missing or blank on 1905 of 1905 rows (100.00%, limit 2.00%)");
    }

    @Test
    void rejectsATruncatedOrShortFeed() {
        ExposureFeedStats truncated = octoberFeed();
        truncated.rows = 1200;
        truncated.staged = 1200;
        assertThat(truncated.problems()).containsExactly(
                "meta.total_data is 1905 but the data array has 1200 rows",
                "only 1200 rows with an epaid, expected more than 1500");

        ExposureFeedStats noData = new ExposureFeedStats();
        assertThat(noData.problems()).containsExactly("no data array");
    }

    @Test
    void feedWithoutMetaOrSuccessIsJudgedOnItsRows() {
        ExposureFeedStats stats = octoberFeed();
        stats.metaTotal = null;
        stats.success = null;
        assertThat(stats.problems()).isEmpty();
    }
}
