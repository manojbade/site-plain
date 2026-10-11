package com.siteplain.web.limits;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Who a request comes from, for per-client rate limits. Never logged and never stored beyond the
 * in-memory buckets.
 *
 * <p>How the address reaches the app on Railway behind Cloudflare: browser, then Cloudflare (which
 * sets CF-Connecting-IP and appends to X-Forwarded-For), then Railway's edge, then this container.
 * Railway documents X-Real-IP as "the client's remote IP", and Railway staff state the edge
 * overwrites X-Real-IP and X-Forwarded-For with its own values, so a copy sent by the client never
 * arrives; when the connection comes from Cloudflare's published ranges, the edge fills X-Real-IP
 * from CF-Connecting-IP. That makes X-Real-IP the visitor's address whether or not the request went
 * through Cloudflare, and the header this class reads (siteplain.limits.client-ip-header).
 *
 * <p>Not used: CF-Connecting-IP directly (anyone who reaches the Railway origin without Cloudflare
 * can set it to anything), and request.getRemoteAddr(), which server.forward-headers-strategy=framework
 * replaces with the first X-Forwarded-For entry, the one a client controls when a proxy appends.
 * getRemoteAddr() is only the fallback when the header is absent (local runs, tests), where the
 * peer is the client itself.
 *
 * <p>Spoofability: this rests on Railway's edge behavior, which is stated by staff and in the
 * networking docs but not contractual; Railway has also shipped a regression where X-Real-IP held a
 * CDN edge address, which would put many visitors in one bucket. Hence two safeguards: the per-client
 * limits are generous, and the global concurrency caps (WorkLimits) protect cost whatever this
 * returns. IPv6 clients are grouped by /64, the block one subscriber usually gets, so rotating
 * addresses inside it does not mint new buckets.
 */
public final class ClientAddress {

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private ClientAddress() {
    }

    /** A bucket key for the client: "v4:a.b.c.d", "v6:<first 64 bits in hex>", or "unknown". */
    public static String of(HttpServletRequest request, String headerName) {
        String fromHeader = headerName == null || headerName.isBlank() ? null : normalize(request.getHeader(headerName));
        if (fromHeader != null) {
            return fromHeader;
        }
        String fromPeer = normalize(request.getRemoteAddr());
        return fromPeer != null ? fromPeer : "unknown";
    }

    /** The key for one textual IP address, or null when it is not an IP literal. Never resolves names. */
    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        var v4 = IPV4.matcher(candidate);
        if (v4.matches()) {
            for (int group = 1; group <= 4; group++) {
                if (Integer.parseInt(v4.group(group)) > 255) {
                    return null;
                }
            }
            return "v4:" + candidate;
        }
        // Requiring a colon keeps InetAddress on its literal parser: it never does a DNS lookup for these.
        if (candidate.indexOf(':') < 0 || !IPV6_CHARS.matcher(candidate).matches()) {
            return null;
        }
        try {
            byte[] bytes = InetAddress.getByName(candidate).getAddress();
            if (bytes.length == 4) {
                return "v4:" + InetAddress.getByAddress(bytes).getHostAddress();
            }
            return "v6:" + HexFormat.of().formatHex(bytes, 0, 8);
        } catch (UnknownHostException ex) {
            return null;
        }
    }
}
