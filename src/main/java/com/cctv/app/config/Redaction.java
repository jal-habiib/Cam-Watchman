package com.cctv.app.config;

/** Credential redaction for anything that may reach a log line, dialog or document (plan 1b/8d). */
public final class Redaction {

    private Redaction() {
    }

    /**
     * Masks user-info in URLs ({@code rtsp://user:pass@host/x} → {@code rtsp://***@host/x}) and
     * the values of obviously secret query parameters. Safe to call on null and on non-URL text.
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text.replaceAll("(?i)\\b(rtsp|rtsps|http|https)://[^/\\s@]+@", "$1://***@");
        out = out.replaceAll("(?i)([?&](?:password|pass|pwd|token|secret|key)=)[^&\\s]+", "$1***");
        return out;
    }
}
