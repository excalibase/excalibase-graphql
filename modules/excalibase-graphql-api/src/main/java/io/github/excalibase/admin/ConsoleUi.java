package io.github.excalibase.admin;

import java.util.Locale;

/**
 * Which console {@code /console} serves. {@code next} is the built one and the default; {@code legacy} is the
 * plain-script console, kept as an escape hatch. With {@code next} the old one is still reachable at
 * {@code /console/legacy} until it is retired. Read strictly, like the console's
 * own flag: a misspelling must refuse the start, not pick one of the two silently.
 */
public enum ConsoleUi {
    LEGACY, NEXT;

    public static ConsoleUi parse(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return switch (text) {
            case "legacy" -> LEGACY;
            case "next" -> NEXT;
            default -> throw new IllegalStateException("EXCALIBASE_CONSOLE_UI must be legacy or next but was '"
                    + value + "'");
        };
    }
}
