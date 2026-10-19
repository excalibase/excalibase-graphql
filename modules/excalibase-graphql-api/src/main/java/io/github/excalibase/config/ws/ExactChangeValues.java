package io.github.excalibase.config.ws;

import io.github.excalibase.ExactNumbers;

import java.math.BigDecimal;

/**
 * A change's bigint and decimal columns as the strings a query reads them as (see {@link ExactNumbers}), so a
 * client compares one value against the other unchanged.
 */
final class ExactChangeValues {

    private ExactChangeValues() {
    }

    static Object format(Object value, String type) {
        if (!(value instanceof Number number) || !ExactNumbers.isExact(type)) {
            return value;
        }
        return number instanceof BigDecimal decimal ? decimal.toPlainString()
                : new BigDecimal(number.toString()).toPlainString();
    }
}
