package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ResultsTokensTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final ResultsLink LINK = new ResultsLink("57 SALEM ST, WOBURN, MA, 01801", "57 Salem Street, Woburn",
            42.4793, -71.1523, "MA", "census", null, 1_791_600_000L);

    @Test
    void roundTripsAndHidesTheContents() {
        ResultsTokens tokens = new ResultsTokens(KEY);
        String token = tokens.encode(LINK);

        assertThat(tokens.decode(token)).contains(LINK);
        assertThat(token).matches("[A-Za-z0-9_-]+").doesNotContain("SALEM");
        String decodedBytes = new String(Base64.getUrlDecoder().decode(token), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(decodedBytes).doesNotContain("SALEM").doesNotContain("Woburn").doesNotContain("42.4793");
        // A fresh nonce each time, so the same lookup does not give the same link.
        assertThat(tokens.encode(LINK)).isNotEqualTo(token);
        assertThat(token.length()).isLessThan(400);
    }

    @Test
    void rejectsChangedTruncatedOrMadeUpLinks() {
        ResultsTokens tokens = new ResultsTokens(KEY);
        byte[] bytes = Base64.getUrlDecoder().decode(tokens.encode(LINK));
        for (int i = 0; i < bytes.length; i++) {
            byte[] changed = bytes.clone();
            changed[i] ^= 0x01;
            assertThat(tokens.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(changed)))
                    .as("bit flipped at byte %s", i).isEmpty();
        }
        String token = tokens.encode(LINK);
        assertThat(tokens.decode(token.substring(0, token.length() - 4))).isEmpty();
        assertThat(tokens.decode("not a token")).isEmpty();
        assertThat(tokens.decode("")).isEmpty();
        assertThat(tokens.decode(null)).isEmpty();
        assertThat(tokens.decode("A".repeat(ResultsTokens.MAX_TOKEN_LENGTH + 1))).isEmpty();
    }

    @Test
    void aLinkFromAnotherKeyIsRejected() {
        String otherKey = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
        String token = new ResultsTokens(otherKey).encode(LINK);
        assertThat(new ResultsTokens(KEY).decode(token)).isEmpty();
        assertThat(new ResultsTokens(otherKey).decode(token)).contains(LINK);
    }

    @Test
    void withoutAKeyUsesARandomOneAndWarns(CapturedOutput output) {
        ResultsTokens first = new ResultsTokens("");
        ResultsTokens second = new ResultsTokens(" ");
        assertThat(first.decode(first.encode(LINK))).contains(LINK);
        assertThat(second.decode(first.encode(LINK))).isEmpty();
        assertThat(output.getOut() + output.getErr())
                .contains("SITEPLAIN_RESULTS_TOKEN_KEY is not set: using a random key");
    }

    @Test
    void aMalformedKeyStopsStartup() {
        assertThatThrownBy(() -> new ResultsTokens("not base64!")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid base64");
        assertThatThrownBy(() -> new ResultsTokens(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
