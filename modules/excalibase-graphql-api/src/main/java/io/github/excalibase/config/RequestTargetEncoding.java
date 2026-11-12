package io.github.excalibase.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Percent-encodes what a strict URI parser refuses but browsers, curl and PostgREST clients send:
 * braces, quotes, brackets, a lone percent sign. Valid escapes and everything already legal stay as written.
 */
public final class RequestTargetEncoding {

    private static final String LEGAL_PUNCTUATION = "-._~!$&'()*+,;=:@/?#";

    private RequestTargetEncoding() {
    }

    public static String encode(String target) {
        int queryStart = target.indexOf('?');
        if (queryStart < 0) {
            return encodeText(target);
        }
        String query = Arrays.stream(target.substring(queryStart + 1).split("&", -1))
                .filter(parameter -> !hasMalformedEscape(parameter))
                .collect(Collectors.joining("&"));
        String path = encodeText(target.substring(0, queryStart));
        return query.isEmpty() ? path : path + "?" + encodeText(query);
    }

    /** A parameter the servlet container could not decode was left out of the request. */
    private static boolean hasMalformedEscape(String parameter) {
        for (int index = parameter.indexOf('%'); index >= 0; index = parameter.indexOf('%', index + 1)) {
            if (!hasHexPair(parameter, index)) {
                return true;
            }
        }
        return false;
    }

    private static String encodeText(String target) {
        StringBuilder encoded = new StringBuilder(target.length() + 8);
        for (int index = 0; index < target.length(); index++) {
            char current = target.charAt(index);
            if (current == '%' && hasHexPair(target, index)) {
                encoded.append(current);
            } else if (isLegal(current)) {
                encoded.append(current);
            } else {
                appendEscaped(encoded, target, index);
                index += Character.isHighSurrogate(current) ? 1 : 0;
            }
        }
        return encoded.toString();
    }

    private static boolean hasHexPair(String target, int percentIndex) {
        return percentIndex + 2 < target.length()
                && Character.digit(target.charAt(percentIndex + 1), 16) >= 0
                && Character.digit(target.charAt(percentIndex + 2), 16) >= 0;
    }

    private static boolean isLegal(char current) {
        return current < 128 && (Character.isLetterOrDigit(current) || LEGAL_PUNCTUATION.indexOf(current) >= 0);
    }

    private static void appendEscaped(StringBuilder encoded, String target, int index) {
        int length = Character.isHighSurrogate(target.charAt(index)) ? 2 : 1;
        for (byte octet : target.substring(index, Math.min(index + length, target.length()))
                .getBytes(StandardCharsets.UTF_8)) {
            encoded.append('%').append(String.format("%02X", octet & 0xFF));
        }
    }
}
