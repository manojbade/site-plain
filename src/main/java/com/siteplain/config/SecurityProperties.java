package com.siteplain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Response security headers. See SecurityHeadersFilter. */
@ConfigurationProperties(prefix = "siteplain.security")
public class SecurityProperties {

    public enum CspMode {
        /** Content-Security-Policy: the browser blocks anything the policy does not allow. */
        ENFORCE,
        /** Content-Security-Policy-Report-Only: the browser only reports what it would have blocked. */
        REPORT_ONLY
    }

    private Csp csp = new Csp();

    public static class Csp {

        /**
         * enforce (default) or report-only (SITEPLAIN_SECURITY_CSP_MODE). Switch to report-only on
         * Railway, without a code change, if production shows the policy blocking something it should not.
         */
        private CspMode mode = CspMode.ENFORCE;

        public CspMode getMode() {
            return mode;
        }

        public void setMode(CspMode mode) {
            this.mode = mode;
        }
    }

    public Csp getCsp() {
        return csp;
    }

    public void setCsp(Csp csp) {
        this.csp = csp;
    }
}
