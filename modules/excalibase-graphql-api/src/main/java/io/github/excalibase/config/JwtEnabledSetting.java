package io.github.excalibase.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PostConstruct;
import java.util.Locale;

/**
 * {@code app.security.jwt-enabled} is read by conditions on beans and by plain values, so a spelling one of them
 * reads as "on" and another as "off" would leave authentication half applied. Only true and false are accepted, in
 * any case; anything else stops start-up. A blank value (an unset variable) means the default, on.
 */
@Context
public class JwtEnabledSetting {

    /** The conditions that switch authentication on and off compare the property with these, in any case. */
    public static final String OFF_PATTERN = "(?i)\\s*false\\s*";
    public static final String ON_PATTERN = "(?is)(?!\\s*false\\s*$).*";

    private final String value;

    public JwtEnabledSetting(@Value("${app.security.jwt-enabled:true}") String value) {
        this.value = value;
    }

    @PostConstruct
    void check() {
        parse(value);
    }

    static boolean parse(String value) {
        String text = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "", "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException("app.security.jwt-enabled must be true or false but was '"
                    + value + "'; any other spelling is refused so authentication cannot be switched off by one.");
        };
    }
}
