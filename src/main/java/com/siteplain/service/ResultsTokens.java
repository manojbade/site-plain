package com.siteplain.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Turns a ResultsLink into the opaque r= value of a results link and back. AES-256-GCM: the link
 * is encrypted, so the address and coordinates stay out of the URL (browser history, analytics,
 * logs), and authenticated, so a changed or made-up link is rejected instead of rendering a
 * report the lookup never produced.
 *
 * <p>Format, base64url without padding: one version byte (1), a random 12-byte nonce, then the
 * ciphertext of the JSON payload with GCM's 16-byte tag; the associated data names the format
 * version. Links do not expire: a shared link keeps working for as long as the key
 * (SITEPLAIN_RESULTS_TOKEN_KEY) stays the same. The payload carries issued-at, so an expiry can
 * be added later without changing the format.
 */
@Service
public class ResultsTokens {

    /** Longest r= value accepted; a link from a 200-character address is well under half of it. */
    public static final int MAX_TOKEN_LENGTH = 2048;

    private static final Logger log = LoggerFactory.getLogger(ResultsTokens.class);
    private static final byte VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final byte[] ASSOCIATED_DATA = "site-plain results link v1".getBytes(StandardCharsets.US_ASCII);

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ResultsTokens(@Value("${siteplain.results.tokenKey:}") String base64Key) {
        this.key = new SecretKeySpec(keyBytes(base64Key), "AES");
    }

    private byte[] keyBytes(String base64Key) {
        if (!StringUtils.hasText(base64Key)) {
            log.warn("SITEPLAIN_RESULTS_TOKEN_KEY is not set: using a random key, so results links made now "
                    + "stop working when the app restarts. Set it in any deployment (see docs).");
            byte[] generated = new byte[KEY_BYTES];
            random.nextBytes(generated);
            return generated;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("SITEPLAIN_RESULTS_TOKEN_KEY is not valid base64");
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException("SITEPLAIN_RESULTS_TOKEN_KEY must decode to 32 bytes, not " + decoded.length);
        }
        return decoded;
    }

    public String encode(ResultsLink link) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(ASSOCIATED_DATA);
            byte[] sealed = cipher.doFinal(json.writeValueAsBytes(link));
            ByteBuffer token = ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length);
            token.put(VERSION).put(nonce).put(sealed);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token.array());
        } catch (GeneralSecurityException | java.io.IOException ex) {
            throw new IllegalStateException("Could not encode a results link", ex);
        }
    }

    /** The link's contents, or empty for anything this key did not issue (changed, truncated, made up). */
    public Optional<ResultsLink> decode(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length < 1 + NONCE_BYTES + TAG_BITS / 8 || bytes[0] != VERSION) {
                return Optional.empty();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 1, NONCE_BYTES));
            cipher.updateAAD(ASSOCIATED_DATA);
            byte[] plain = cipher.doFinal(bytes, 1 + NONCE_BYTES, bytes.length - 1 - NONCE_BYTES);
            return Optional.of(json.readValue(plain, ResultsLink.class));
        } catch (IllegalArgumentException | GeneralSecurityException | java.io.IOException ex) {
            return Optional.empty();
        }
    }
}
