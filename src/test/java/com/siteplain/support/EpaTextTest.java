package com.siteplain.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EpaTextTest {

    @Test
    void lines_splitOnEveryLineBreakSpelling() {
        assertThat(EpaText.lines("One<br/>Two<br>Three<BR />Four")).containsExactly("One", "Two", "Three", "Four");
    }

    @Test
    void lines_keepBlankLinesAndOtherMarkupAsText() {
        assertThat(EpaText.lines("A<br/><br/>B <script>x</script> & C"))
                .containsExactly("A", "", "B <script>x</script> & C");
    }

    @Test
    void lines_areEmptyForMissingText() {
        assertThat(EpaText.lines(null)).isEmpty();
        assertThat(EpaText.lines("  ")).isEmpty();
    }
}
