package com.gongyo.orderly;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

/** Display metadata only: never use this name as the UUID cache file path. */
public final class ExportFileName {
    private static final String FALLBACK = "유란노트";
    private static final int MAX_TITLE_CODEPOINTS = 80;

    private ExportFileName() { }

    /**
     * Uses the already chosen preparation instant and explicit phone timezone.
     * The 80-codepoint limit applies to the sanitized title, before the suffix.
     */
    public static String create(String rawTitle, long preparedAtMillis,
            String format, TimeZone timezone) {
        if (!"pdf".equals(format) && !"xlsx".equals(format)) {
            throw new IllegalArgumentException("format must be exactly pdf or xlsx");
        }
        Objects.requireNonNull(timezone, "timezone");

        SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US);
        timestamp.setTimeZone(timezone);
        return title(rawTitle) + "_" + timestamp.format(new Date(preparedAtMillis))
                + "." + format;
    }

    private static String title(String rawTitle) {
        if (rawTitle == null) {
            return FALLBACK;
        }
        StringBuilder sanitized = new StringBuilder();
        boolean hasOriginalUsableCharacter = false;
        for (int offset = 0; offset < rawTitle.length();) {
            int codepoint = rawTitle.codePointAt(offset);
            offset += Character.charCount(codepoint);
            if (forbidden(codepoint)) {
                sanitized.append('_');
            } else {
                sanitized.appendCodePoint(codepoint);
                if (!space(codepoint)) {
                    hasOriginalUsableCharacter = true;
                }
            }
        }
        if (!hasOriginalUsableCharacter) {
            return FALLBACK;
        }
        String result = trimSpaces(sanitized.toString());
        if (result.codePointCount(0, result.length()) > MAX_TITLE_CODEPOINTS) {
            result = result.substring(0, result.offsetByCodePoints(0, MAX_TITLE_CODEPOINTS));
            result = trimSpaces(result);
        }
        return result.isEmpty() ? FALLBACK : result;
    }

    private static boolean forbidden(int codepoint) {
        return Character.isISOControl(codepoint)
                || codepoint == '\\' || codepoint == '/' || codepoint == ':'
                || codepoint == '*' || codepoint == '?' || codepoint == '"'
                || codepoint == '<' || codepoint == '>' || codepoint == '|';
    }

    private static boolean space(int codepoint) {
        return Character.isWhitespace(codepoint) || Character.isSpaceChar(codepoint);
    }

    private static String trimSpaces(String text) {
        int start = 0;
        int end = text.length();
        while (start < end) {
            int codepoint = text.codePointAt(start);
            if (!space(codepoint)) {
                break;
            }
            start += Character.charCount(codepoint);
        }
        while (start < end) {
            int codepoint = text.codePointBefore(end);
            if (!space(codepoint)) {
                break;
            }
            end -= Character.charCount(codepoint);
        }
        return text.substring(start, end);
    }
}
